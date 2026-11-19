package stirling.software.SPDF.license;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Hidden;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;

/**
 * 授权与试用接口。
 *
 * <p>前端启动时调 {@code /status} 拿剩余次数；每次执行 PDF 操作前调 {@code /consume} 扣一次， 返回 {@code ok=false}
 * 就弹付费窗。所有接口都返回 200 + 扁平 JSON，业务状态放在 {@code ok} 字段里——前端不需要区分 HTTP 错误码。
 *
 * <p>计次主体由 LicenseService 决定：桌面版用本机硬件码，在线演示实例（{@code STIRLING_LICENSE_MODE=online}）用访问者
 * IP，所以这里必须把请求带下去。
 */
@Hidden
@RestController
@RequestMapping("/api/v1/license")
@RequiredArgsConstructor
public class LicenseController {

    private final LicenseService licenseService;

    /** 取真实来源 IP。在线实例直连 8080，没有反向代理，remoteAddr 即客户端 IP。 */
    private static String clientIp(HttpServletRequest req) {
        String h = req.getHeader("X-Forwarded-For");
        if (h != null && !h.isBlank()) {
            String first = h.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        h = req.getHeader("X-Real-IP");
        if (h != null && !h.isBlank()) {
            return h.trim();
        }
        return req.getRemoteAddr();
    }

    private String subject(HttpServletRequest req) {
        return licenseService.subjectFor(licenseService.isOnlineMode() ? clientIp(req) : null);
    }

    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> status(HttpServletRequest req) {
        Map<String, Object> r = new LinkedHashMap<>(licenseService.checkin(subject(req)));
        r.putIfAbsent("ok", true);
        return r;
    }

    @PostMapping(value = "/consume", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> consume(
            HttpServletRequest req, @RequestBody(required = false) Map<String, Object> body) {
        String feature = body == null ? "" : String.valueOf(body.getOrDefault("feature", ""));
        Map<String, Object> r = new LinkedHashMap<>(licenseService.consume(subject(req), feature));
        r.putIfAbsent("ok", true);
        return r;
    }

    @PostMapping(value = "/redeem", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> redeem(
            HttpServletRequest req, @RequestBody(required = false) Map<String, Object> body) {
        String code = body == null ? "" : String.valueOf(body.getOrDefault("code", ""));
        Map<String, Object> r = new LinkedHashMap<>(licenseService.redeem(subject(req), code));
        r.putIfAbsent("ok", false);
        return r;
    }

    @PostMapping(value = "/apply", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> apply(
            HttpServletRequest req, @RequestBody(required = false) Map<String, Object> body) {
        String email = body == null ? "" : String.valueOf(body.getOrDefault("email", ""));
        Map<String, Object> r = new LinkedHashMap<>(licenseService.apply(subject(req), email));
        r.putIfAbsent("ok", false);
        return r;
    }

    @GetMapping(value = "/machine", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> machine(HttpServletRequest req) {
        return Map.of(
                "machine_code", subject(req),
                "online_mode", licenseService.isOnlineMode());
    }
}
