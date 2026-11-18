package stirling.software.SPDF.license;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 授权服务客户端。
 *
 * <p>机器码 = "S" + SHA-256(各网卡 MAC | 主机名 | 固定盐) 的前 32 位十六进制，共 33 个字符。 前缀 "S" 让授权服务端把它识别为 Stirling
 * 产品线（PDF 工具箱走 32 位、无前缀）。
 *
 * <p>契约：所有方法都不抛异常。授权服务不可达时返回 {@code ok=true, degraded=true} 放行—— 商业上宁可少算一次，也不能让断网或服务端抖动把客户的软件锁死。
 */
@Slf4j
@Service
public class LicenseService {

    private static final String DEFAULT_SERVER = "https://lic.cjers.top";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    private volatile String cachedMachineCode;

    /** 授权服务根地址，可用 STIRLING_LICENSE_SERVER 覆盖（本地调试用）。 */
    public String server() {
        String env = System.getenv("STIRLING_LICENSE_SERVER");
        return (env == null || env.isBlank()) ? DEFAULT_SERVER : env.replaceAll("/+$", "");
    }

    public synchronized String machineCode() {
        if (cachedMachineCode != null) {
            return cachedMachineCode;
        }
        StringBuilder sb = new StringBuilder();
        try {
            Enumeration<java.net.NetworkInterface> ifaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                java.net.NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                byte[] mac = ni.getHardwareAddress();
                if (mac != null && mac.length > 0) {
                    sb.append(HexFormat.of().formatHex(mac)).append('|');
                }
            }
        } catch (Exception e) {
            log.debug("读取网卡信息失败，改用主机名派生机器码: {}", e.toString());
        }
        sb.append(hostName()).append("|stirling-zh-v1");
        String hex = sha256Hex(sb.toString());
        cachedMachineCode = "S" + hex.substring(0, 32).toUpperCase();
        return cachedMachineCode;
    }

    private static String hostName() {
        String h = System.getenv("HOSTNAME");
        if (h == null || h.isBlank()) {
            try {
                h = java.net.InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                h = "unknown";
            }
        }
        return h;
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode()) + Integer.toHexString(s.length() * 31);
        }
    }

    // ---------- 与授权服务通信 ----------

    /** 查询状态：首次访问会在服务端建档并发放试用次数。 */
    public Map<String, Object> checkin() {
        return post("/api/checkin", Map.of("mid", machineCode(), "host", hostName()));
    }

    /** 消耗一次试用。已付费时服务端不计次。 */
    public Map<String, Object> consume(String feature) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mid", machineCode());
        body.put("feature", feature == null ? "" : feature);
        return post("/api/consume", body);
    }

    /** 用兑换码激活。 */
    public Map<String, Object> redeem(String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mid", machineCode());
        body.put("code", code == null ? "" : code.trim());
        return post("/api/redeem", body);
    }

    /** 提交邮箱授权申请（人工开通）。 */
    public Map<String, Object> apply(String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mid", machineCode());
        body.put("email", email == null ? "" : email.trim());
        body.put("host", hostName());
        return post("/api/apply", body);
    }

    private Map<String, Object> post(String path, Map<String, Object> payload) {
        String json = toJson(payload);
        try {
            HttpRequest req =
                    HttpRequest.newBuilder(URI.create(server() + path))
                            .timeout(REQUEST_TIMEOUT)
                            .header("Content-Type", "application/json")
                            .header("User-Agent", "Stirling-PDF-ZH")
                            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                            .build();
            HttpResponse<String> resp =
                    http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                log.warn("授权服务返回 {}：{}", resp.statusCode(), abbreviate(resp.body()));
                return degraded("授权服务异常");
            }
            Map<String, Object> parsed = parseFlat(resp.body());
            if (parsed == null) {
                return degraded("授权服务响应无法解析");
            }
            parsed.put("machine_code", machineCode());
            return parsed;
        } catch (Exception e) {
            log.warn("连接授权服务失败：{}", e.toString());
            return degraded("无法连接授权服务");
        }
    }

    private Map<String, Object> degraded(String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("degraded", true);
        m.put("msg", reason);
        m.put("machine_code", machineCode());
        return m;
    }

    // ---------- 极简 JSON（避免与 Jackson 版本耦合） ----------

    private static String toJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(escape(String.valueOf(v))).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** 只解析扁平对象（授权服务返回的就是扁平结构）。 */
    private static Map<String, Object> parseFlat(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (!t.startsWith("{")) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        int i = 1;
        int n = t.length();
        while (i < n) {
            while (i < n && (Character.isWhitespace(t.charAt(i)) || t.charAt(i) == ',')) {
                i++;
            }
            if (i >= n || t.charAt(i) == '}') {
                break;
            }
            if (t.charAt(i) != '"') {
                return null;
            }
            int keyEnd = findStringEnd(t, i + 1);
            if (keyEnd < 0) {
                return null;
            }
            String key = unescape(t.substring(i + 1, keyEnd));
            i = keyEnd + 1;
            while (i < n && t.charAt(i) != ':') {
                i++;
            }
            i++;
            while (i < n && Character.isWhitespace(t.charAt(i))) {
                i++;
            }
            if (i >= n) {
                return null;
            }
            char c = t.charAt(i);
            if (c == '"') {
                int ve = findStringEnd(t, i + 1);
                if (ve < 0) {
                    return null;
                }
                out.put(key, unescape(t.substring(i + 1, ve)));
                i = ve + 1;
            } else if (c == '{' || c == '[') {
                int depth = 0;
                int j = i;
                while (j < n) {
                    char d = t.charAt(j);
                    if (d == '{' || d == '[') {
                        depth++;
                    } else if (d == '}' || d == ']') {
                        depth--;
                        if (depth == 0) {
                            j++;
                            break;
                        }
                    }
                    j++;
                }
                out.put(key, t.substring(i, j));
                i = j;
            } else {
                int j = i;
                while (j < n && t.charAt(j) != ',' && t.charAt(j) != '}') {
                    j++;
                }
                String raw = t.substring(i, j).trim();
                if ("true".equals(raw) || "false".equals(raw)) {
                    out.put(key, Boolean.valueOf(raw));
                } else {
                    try {
                        out.put(key, Integer.valueOf(raw));
                    } catch (NumberFormatException ex) {
                        out.put(key, raw);
                    }
                }
                i = j;
            }
        }
        return out;
    }

    private static int findStringEnd(String t, int from) {
        for (int i = from; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char nx = s.charAt(++i);
            switch (nx) {
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case 'u' -> {
                    if (i + 4 < s.length()) {
                        sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                }
                default -> sb.append(nx);
            }
        }
        return sb.toString();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
