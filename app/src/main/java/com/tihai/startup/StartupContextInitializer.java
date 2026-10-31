package com.tihai.startup;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

/**
 * 在主上下文 refresh 之前、Web 端口绑定之前执行启动自检。
 *
 * <p>不使用 EnvironmentPostProcessor：spring-cloud-starter-bootstrap 会让后者
 * 先在 bootstrap 环境执行，此时 application.yml 尚未加载，读不到数据源。
 */
public class StartupContextInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        ConfigurableEnvironment environment = context.getEnvironment();
        String logPath = StartupFailureReporter.logPath();
        long start = System.currentTimeMillis();

        List<String> origins;
        try {
            origins = org.springframework.boot.context.properties.bind.Binder.get(environment)
                    .bind("cors.allow.origins",
                            org.springframework.boot.context.properties.bind.Bindable.listOf(String.class))
                    .orElse(Collections.<String>emptyList());
        } catch (RuntimeException e) {
            origins = Collections.emptyList();
        }
        try {
            CorsOriginSet originSet = new CorsOriginResolver().resolve(origins);
            StartupPhaseLog.pass(StartupPhase.CORS, start,
                    "origins=" + originSet.getSource() + " count=" + originSet.count());
        } catch (CorsConfigException e) {
            StartupFailure failure = new StartupFailure(
                    now(), StartupPhase.CORS, StartupErrorCode.CORS,
                    "跨域配置非法", "检查 cors.allow.origins：只允许 http(s)://主机[:端口]，且不能使用 *",
                    logPath, e.getMessage());
            StartupFailureReporter.report(failure);
            System.exit(1);
            return;
        }

        String url = environment.getProperty("spring.datasource.url");
        String username = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");
        Integer timeout = environment.getProperty(
                "superauto.startup.db-check-timeout-seconds", Integer.class, 5);
        DataSourceSettings settings = new DataSourceSettings(url, username, password,
                timeout == null ? 5 : timeout);

        PreflightResult result = new DataSourcePreflight(settings).check();
        if (!result.isPassed()) {
            StartupFailure failure = new StartupFailure(
                    now(), StartupPhase.DATASOURCE, result.getErrorCode(),
                    result.getReason(), result.getNext(), logPath,
                    "kind=" + result.getKind() + ", endpoint=" + settings.safeEndpoint());
            StartupFailureReporter.report(failure);
            System.exit(1);
            return;
        }
        StartupPhaseLog.pass(StartupPhase.DATASOURCE, start, "db=" + settings.safeEndpoint());
    }

    private static String now() {
        return LocalDateTime.now().format(TIME);
    }
}