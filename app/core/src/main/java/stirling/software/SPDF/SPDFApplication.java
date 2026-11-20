package stirling.software.SPDF;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.github.pixee.security.SystemCommand;

import jakarta.annotation.PostConstruct;

import lombok.extern.slf4j.Slf4j;

import stirling.software.common.configuration.AppConfig;
import stirling.software.common.configuration.ConfigInitializer;
import stirling.software.common.configuration.InstallationPathConfig;
import stirling.software.common.model.ApplicationProperties;

@Slf4j
@EnableScheduling
@SpringBootApplication(
        scanBasePackages = {
            "stirling.software.SPDF",
            "stirling.software.common",
            "stirling.software.proprietary",
            "stirling.software.saas"
        })
public class SPDFApplication {

    private static final Pattern PORT_SUFFIX_PATTERN = Pattern.compile(".+:\\d+$");
    private static final Pattern URL_SCHEME_PATTERN =
            Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://.*");
    private static final Pattern TRAILING_SLASH_PATTERN = Pattern.compile("/+$");
    private static String serverPortStatic;
    private static String baseUrlStatic;
    private static String contextPathStatic;

    private final AppConfig appConfig;
    private final Environment env;
    private final ApplicationProperties applicationProperties;

    public SPDFApplication(
            AppConfig appConfig, Environment env, ApplicationProperties applicationProperties) {
        this.appConfig = appConfig;
        this.env = env;
        this.applicationProperties = applicationProperties;
    }

    /** 进程存活期间持有；锁与 Channel 同生命周期，提前 close 会让锁失效。 */
    private static FileChannel instanceLockChannel;

    /**
     * 本机是否已有实例在运行。
     *
     * <p>用文件锁而非端口探测：端口可能被改配置或别的程序占用，而锁文件由操作系统在进程结束时自动释放， 不会留下假阳性。
     *
     * @return true 表示已有实例，调用方应直接结束本次启动
     */
    private static boolean anotherInstanceRunning() {
        try {
            Path lockPath =
                    Path.of(InstallationPathConfig.getSettingsPath())
                            .resolveSibling(".instance.lock");
            Files.createDirectories(lockPath.getParent());
            instanceLockChannel =
                    FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = instanceLockChannel.tryLock();
            if (lock != null) {
                return false;
            }
            instanceLockChannel.close();
            instanceLockChannel = null;
            String port = resolveServerPort();
            log.warn("已有实例在运行，打开浏览器指向 localhost:{}", port);
            browseLocal(port);
            return true;
        } catch (Exception e) {
            log.debug("单实例检测失败，按全新启动处理: {}", e.toString());
            return false;
        }
    }

    private static String resolveServerPort() {
        String port = System.getenv("SERVER_PORT");
        return (port == null || port.isBlank()) ? "8080" : port;
    }

    private static void browseLocal(String port) {
        try {
            Desktop desktop = Desktop.isDesktopSupported() ? Desktop.getDesktop() : null;
            if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(URI.create("http://localhost:" + port + "/"));
            }
        } catch (Exception e) {
            log.debug("打开浏览器失败: {}", e.toString());
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        // 单实例保护：桌面版用户容易连点两次图标，第二个实例会和第一个抢同一个 H2
        // 数据库文件锁（MVStoreException: The file is locked），JPA 初始化失败，
        // 表现为「装了但打不开 / 用户管理空白」。已有实例在跑时直接把浏览器指过去。
        if (anotherInstanceRunning()) {
            return;
        }

        SpringApplication app = new SpringApplication(SPDFApplication.class);

        Properties props = new Properties();

        app.setAdditionalProfiles(getActiveProfile(args));

        ConfigInitializer initializer = new ConfigInitializer();
        try {
            initializer.ensureConfigExists();
        } catch (IOException | URISyntaxException e) {
            log.error("Error initialising configuration", e);
        }
        Map<String, String> propertyFiles = new HashMap<>();

        // External config files
        Path settingsPath = Path.of(InstallationPathConfig.getSettingsPath());
        log.info("Settings file: {}", settingsPath.toString());
        if (Files.exists(settingsPath)) {
            propertyFiles.put(
                    "spring.config.additional-location", "file:" + settingsPath.toString());
        } else {
            log.warn("External configuration file '{}' does not exist.", settingsPath.toString());
        }

        Path customSettingsPath = Path.of(InstallationPathConfig.getCustomSettingsPath());
        log.info("Custom settings file: {}", customSettingsPath.toString());
        if (Files.exists(customSettingsPath)) {
            String existingLocation =
                    propertyFiles.getOrDefault("spring.config.additional-location", "");
            if (!existingLocation.isEmpty()) {
                existingLocation += ",";
            }
            propertyFiles.put(
                    "spring.config.additional-location",
                    existingLocation + "file:" + customSettingsPath.toString());
        } else {
            log.warn(
                    "Custom configuration file '{}' does not exist.",
                    customSettingsPath.toString());
        }
        Properties finalProps = new Properties();

        if (!propertyFiles.isEmpty()) {
            finalProps.putAll(
                    Collections.singletonMap(
                            "spring.config.additional-location",
                            propertyFiles.get("spring.config.additional-location")));
        }

        if (!props.isEmpty()) {
            finalProps.putAll(props);
        }
        app.setDefaultProperties(finalProps);

        app.run(args);

        // Ensure directories are created
        try {
            Files.createDirectories(Path.of(InstallationPathConfig.getTemplatesPath()));
            Files.createDirectories(Path.of(InstallationPathConfig.getStaticPath()));
        } catch (IOException e) {
            log.error("Error creating directories: {}", e.getMessage());
        }

        printStartupLogs();
    }

    @PostConstruct
    public void init() {
        String backendUrl = appConfig.getBackendUrl();
        String contextPath = appConfig.getContextPath();
        String serverPort = appConfig.getServerPort();
        baseUrlStatic = normalizeBackendUrl(backendUrl, serverPort);
        contextPathStatic = contextPath;
        serverPortStatic = serverPort;
        String url = buildFullUrl(baseUrlStatic, serverPortStatic, contextPathStatic);

        // Log Tauri mode information
        if (Boolean.parseBoolean(System.getProperty("STIRLING_PDF_TAURI_MODE", "false"))) {
            String parentPid = System.getenv("TAURI_PARENT_PID");
            log.info(
                    "Running in Tauri mode. Parent process PID: {}",
                    parentPid != null ? parentPid : "not set");
        }
        // Standard browser opening logic
        String browserOpenEnv = env.getProperty("BROWSER_OPEN");
        boolean browserOpen = browserOpenEnv != null && "true".equalsIgnoreCase(browserOpenEnv);
        if (browserOpen) {
            try {
                String os = System.getProperty("os.name").toLowerCase();
                Runtime rt = Runtime.getRuntime();

                if (os.contains("win")) {
                    // For Windows
                    SystemCommand.runCommand(rt, "rundll32 url.dll,FileProtocolHandler " + url);
                } else if (os.contains("mac")) {
                    SystemCommand.runCommand(rt, "open " + url);
                } else if (os.contains("nix") || os.contains("nux")) {
                    SystemCommand.runCommand(rt, "xdg-open " + url);
                }
            } catch (IOException e) {
                log.error("Error opening browser: {}", e.getMessage());
            }
        }
    }

    public static void setServerPortStatic(String port) {
        if ("auto".equalsIgnoreCase(port)) {
            // Use Spring Boot's automatic port assignment (server.port=0)
            SPDFApplication.serverPortStatic =
                    "0"; // This will let Spring Boot assign an available port
        } else {
            SPDFApplication.serverPortStatic = port;
        }
    }

    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        String port =
                event.getApplicationContext().getEnvironment().getProperty("local.server.port");
        if (port != null) {
            serverPortStatic = port;
        }
        // Log the actual runtime port for Tauri to parse
        log.info("Stirling-PDF running on port: {}", serverPortStatic);
    }

    private static void printStartupLogs() {
        String url = buildFullUrl(baseUrlStatic, serverPortStatic, contextPathStatic);
        log.info("Navigate to {}", url);
    }

    private static String[] getActiveProfile(String[] args) {
        // 1. Check for explicitly passed profiles
        if (args != null) {
            for (String arg : args) {
                if (arg.startsWith("--spring.profiles.active=")) {
                    String[] provided = arg.substring(arg.indexOf('=') + 1).split(",");
                    if (provided.length > 0) {
                        return provided;
                    }
                }
            }
        }

        // 2. Detect classpath shape and pick the matching profile chain.
        boolean hasSaas = isClassPresent("stirling.software.saas.security.SupabaseSecurityConfig");
        boolean hasSecurity =
                isClassPresent(
                        "stirling.software.proprietary.security.configuration.SecurityConfiguration");

        if (hasSaas) {
            log.info("SaaS features in jar");
            return new String[] {"security", "saas"};
        }
        if (hasSecurity) {
            log.info("Additional features in jar");
            return new String[] {"security"};
        }
        log.info("Without additional features in jar");
        return new String[] {"default"};
    }

    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, SPDFApplication.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static String getStaticBaseUrl() {
        return baseUrlStatic;
    }

    public static String getStaticPort() {
        return serverPortStatic;
    }

    public static String getStaticContextPath() {
        return contextPathStatic;
    }

    private static String buildFullUrl(String backendUrl, String port, String contextPath) {
        String normalizedBase = normalizeBackendUrl(backendUrl, port);

        String normalizedContextPath =
                (contextPath == null || contextPath.isBlank() || "/".equals(contextPath))
                        ? "/"
                        : (contextPath.startsWith("/") ? contextPath : "/" + contextPath);

        return normalizedBase + normalizedContextPath;
    }

    private static String normalizeBackendUrl(String backendUrl, String port) {
        String trimmedBase =
                (backendUrl == null || backendUrl.isBlank())
                        ? "http://localhost"
                        : TRAILING_SLASH_PATTERN.matcher(backendUrl.trim()).replaceAll("");
        boolean hasScheme = URL_SCHEME_PATTERN.matcher(trimmedBase).matches();
        String baseForParsing = hasScheme ? trimmedBase : "http://" + trimmedBase;
        Integer parsedPort = parsePort(port);

        try {
            java.net.URI uri = new java.net.URI(baseForParsing);
            String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
            String host = uri.getHost();
            if (host == null) {
                return appendPortFallback(trimmedBase, parsedPort);
            }

            boolean defaultHttp =
                    "http".equalsIgnoreCase(scheme) && Integer.valueOf(80).equals(parsedPort);
            boolean defaultHttps =
                    "https".equalsIgnoreCase(scheme) && Integer.valueOf(443).equals(parsedPort);

            int effectivePort = uri.getPort();
            if (effectivePort == -1 && parsedPort != null && !defaultHttp && !defaultHttps) {
                effectivePort = parsedPort;
            }

            java.net.URI rebuilt =
                    new java.net.URI(
                            scheme,
                            uri.getUserInfo(),
                            host,
                            effectivePort,
                            uri.getPath(),
                            uri.getQuery(),
                            uri.getFragment());
            return rebuilt.toString();
        } catch (java.net.URISyntaxException e) {
            return appendPortFallback(trimmedBase, parsedPort);
        }
    }

    private static Integer parsePort(String port) {
        if (port == null || port.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(port);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String appendPortFallback(String trimmedBase, Integer port) {
        if (port == null) {
            return trimmedBase;
        }
        if (PORT_SUFFIX_PATTERN.matcher(trimmedBase).matches()) {
            return trimmedBase;
        }
        return trimmedBase + ":" + port;
    }
}
