package com.tihai.startup;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 只负责注入启动日志绝对路径，务必在主配置加载后执行。
 *
 * <p>CORS 与数据源自检放在 {@link StartupContextInitializer}，
 * 因为 bootstrap 上下文会让本处理器先于 application.yml 执行。
 */
public class StartupEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        System.setProperty("superauto.log.file", StartupFailureReporter.resolveLogPath());
    }

    @Override
    public int getOrder() {
        // 排在 ConfigFileApplicationListener（HIGHEST_PRECEDENCE + 10）之后
        return Ordered.HIGHEST_PRECEDENCE + 11;
    }
}