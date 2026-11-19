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
 * <p>两种计次主体，由 {@code STIRLING_LICENSE_MODE} 决定：
 *
 * <ul>
 *   <li>默认（桌面版）：机器码 = "S" + SHA-256(各网卡 MAC | 主机名 | 固定盐) 前 32 位，一台机器一份额度。
 *   <li>{@code online}（在线演示实例）：机器码由访问者 IP 派生，同一个 IP 一份额度；服务器没有反向代理， {@code getRemoteAddr()} 就是真实来源
 *       IP。
 * </ul>
 *
 * <p>两者共用授权服务的 33 字符约定（前缀 "S" 表示 Stirling 产品线）。
 *
 * <p>契约：所有方法都不抛异常。授权服务不可达时返回 {@code ok=true, degraded=true} 放行—— 商业上宁可少算一次，也不能让服务端抖动把客户锁死。
 */
@Slf4j
@Service
public class LicenseService {

    private static final String DEFAULT_SERVER = "https://lic.cjers.top";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    /** 在线演示模式：按访问者 IP 计次，而不是服务器硬件。 */
    private static final boolean ONLINE_MODE =
            "online".equalsIgnoreCase(System.getenv("STIRLING_LICENSE_MODE"));

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    private volatile String cachedHardwareCode;

    public boolean isOnlineMode() {
        return ONLINE_MODE;
    }

    /** 授权服务根地址，可用 STIRLING_LICENSE_SERVER 覆盖（本地调试用）。 */
    public String server() {
        String env = System.getenv("STIRLING_LICENSE_SERVER");
        return (env == null || env.isBlank()) ? DEFAULT_SERVER : env.replaceAll("/+$", "");
    }

    /**
     * 取本次请求的计次主体。
     *
     * @param clientIp 访问者 IP，桌面版传 null
     */
    public String subjectFor(String clientIp) {
        return ONLINE_MODE ? ipCode(clientIp) : hardwareCode();
    }

    private String ipCode(String ip) {
        String seed = (ip == null || ip.isBlank()) ? "unknown" : ip.trim();
        return "S" + sha256Hex("online-v1|" + seed).substring(0, 32).toUpperCase();
    }

    public synchronized String hardwareCode() {
        if (cachedHardwareCode != null) {
            return cachedHardwareCode;
        }
        StringBuilder sb = new StringBuilder();
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (win) {
            // Windows：MachineGuid 是安装时生成且重装系统才变的稳定标识，
            // 配合系统盘卷序列号，比单看 MAC 难改得多
            sb.append(windowsMachineGuid()).append('|');
            sb.append(systemVolumeSerial()).append('|');
        } else {
            // Linux/macOS：machine-id 与主板 UUID 都不随网卡变化
            sb.append(readFileQuiet("/etc/machine-id")).append('|');
            sb.append(readFileQuiet("/sys/class/dmi/id/product_uuid")).append('|');
            sb.append(readFileQuiet("/var/lib/dbus/machine-id")).append('|');
        }
        sb.append(physicalMacs()).append('|');
        sb.append(hostName()).append("|stirling-zh-v2");
        cachedHardwareCode = "S" + sha256Hex(sb.toString()).substring(0, 32).toUpperCase();
        return cachedHardwareCode;
    }

    /** 只用物理网卡（跳过虚拟/回环），避免装个 VPN 或虚拟机就换码。 */
    private static String physicalMacs() {
        StringBuilder sb = new StringBuilder();
        try {
            Enumeration<java.net.NetworkInterface> ifaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                java.net.NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase();
                if (name.startsWith("veth")
                        || name.startsWith("docker")
                        || name.startsWith("br-")
                        || name.startsWith("virbr")
                        || name.startsWith("vmnet")
                        || name.contains("loopback")) {
                    continue;
                }
                byte[] mac = ni.getHardwareAddress();
                if (mac != null && mac.length > 0) {
                    sb.append(HexFormat.of().formatHex(mac)).append(',');
                }
            }
        } catch (Exception e) {
            log.debug("读取网卡信息失败: {}", e.toString());
        }
        return sb.toString();
    }

    private static String readFileQuiet(String path) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(path)).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** 读 Windows 的 MachineGuid（HKLM\SOFTWARE\Microsoft\Cryptography）。 */
    private static String windowsMachineGuid() {
        try {
            Process p =
                    new ProcessBuilder(
                                    "reg",
                                    "query",
                                    "HKLM\\SOFTWARE\\Microsoft\\Cryptography",
                                    "/v",
                                    "MachineGuid")
                            .redirectErrorStream(true)
                            .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile(
                                    "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})")
                            .matcher(out);
            if (m.find()) {
                return m.group(1);
            }
        } catch (Exception e) {
            log.debug("读取 MachineGuid 失败: {}", e.toString());
        }
        return "";
    }

    /** 系统盘卷序列号；换硬盘才会变，重装系统不变。 */
    private static String systemVolumeSerial() {
        try {
            String root = System.getProperty("user.dir", "");
            java.nio.file.Path target =
                    root.isEmpty() ? java.nio.file.Path.of(".") : java.nio.file.Path.of(root);
            java.nio.file.FileStore fs = java.nio.file.Files.getFileStore(target);
            Object vsn = fs.getAttribute("volume:vsn");
            return vsn == null ? "" : String.valueOf(vsn);
        } catch (Exception e) {
            log.debug("读取卷序列号失败: {}", e.toString());
        }
        return "";
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

    public Map<String, Object> checkin(String subject) {
        return post("/api/checkin", Map.of("mid", subject, "host", hostName()));
    }

    public Map<String, Object> consume(String subject, String feature) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mid", subject);
        body.put("feature", feature == null ? "" : feature);
        return post("/api/consume", body);
    }

    public Map<String, Object> redeem(String subject, String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mid", subject);
        body.put("code", code == null ? "" : code.trim());
        return post("/api/redeem", body);
    }

    public Map<String, Object> apply(String subject, String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mid", subject);
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
            parsed.put("machine_code", payload.get("mid"));
            parsed.put("online_mode", ONLINE_MODE);
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
        m.put("online_mode", ONLINE_MODE);
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
