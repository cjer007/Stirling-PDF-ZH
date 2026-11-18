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

import lombok.RequiredArgsConstructor;

/**
 * 授权与试用接口。
 *
 * <p>前端启动时调 {@code /status} 拿剩余次数；每次执行 PDF 操作前调 {@code /consume} 扣一次， 返回 {@code ok=false}
 * 就弹付费窗。所有接口都返回 200 + 扁平 JSON，业务状态放在 {@code ok} 字段里——前端不需要区分 HTTP 错误码。
 */
@Hidden
@RestController
@RequestMapping("/api/v1/license")
@RequiredArgsConstructor
public class LicenseController {

    private final LicenseService licenseService;

    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> status() {
        Map<String, Object> r = new LinkedHashMap<>(licenseService.checkin());
        r.putIfAbsent("ok", true);
        return r;
    }

    @PostMapping(value = "/consume", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> consume(@RequestBody(required = false) Map<String, Object> body) {
        String feature = body == null ? "" : String.valueOf(body.getOrDefault("feature", ""));
        Map<String, Object> r = new LinkedHashMap<>(licenseService.consume(feature));
        r.putIfAbsent("ok", true);
        return r;
    }

    @PostMapping(value = "/redeem", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> redeem(@RequestBody(required = false) Map<String, Object> body) {
        String code = body == null ? "" : String.valueOf(body.getOrDefault("code", ""));
        Map<String, Object> r = new LinkedHashMap<>(licenseService.redeem(code));
        r.putIfAbsent("ok", false);
        return r;
    }

    @PostMapping(value = "/apply", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> apply(@RequestBody(required = false) Map<String, Object> body) {
        String email = body == null ? "" : String.valueOf(body.getOrDefault("email", ""));
        Map<String, Object> r = new LinkedHashMap<>(licenseService.apply(email));
        r.putIfAbsent("ok", false);
        return r;
    }

    @GetMapping(value = "/machine", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> machine() {
        return Map.of("machine_code", licenseService.machineCode());
    }
}
