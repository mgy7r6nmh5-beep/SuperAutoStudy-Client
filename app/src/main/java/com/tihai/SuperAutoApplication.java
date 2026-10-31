package com.tihai;

import com.tihai.startup.StartupContextInitializer;
import com.tihai.startup.StartupFailureReporter;
import com.tihai.startup.StartupPhase;
import com.tihai.startup.StartupPhaseLog;
import com.tihai.startup.StartupReadinessMarker;
import com.tihai.startup.StandaloneModeGuard;
import com.tihai.startup.StandaloneModeState;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * @Copyright : DuanInnovator
 * @Description :启动类
 * @Author : DuanInnovator
 * @CreateTime : 2025/2/28
 * @Link : <a href="https://github.com/DuanInnovator/SuperAutotudy">...</a>
 **/
@SpringBootApplication()
@EnableConfigurationProperties
@MapperScan("com.tihai.mapper")
@EnableScheduling
@Slf4j
public class SuperAutoApplication {

    public static void main(String[] args) throws UnknownHostException {
        // 必须在 SpringApplication 创建之前执行：bootstrap 上下文会早于 application.yml
        StandaloneModeState standalone = StandaloneModeGuard.apply(args);
        // 双保险：即便 EnvironmentPostProcessor 未被加载，logback 也能拿到绝对路径
        System.setProperty("superauto.log.file", StartupFailureReporter.resolveLogPath());
        long start = System.currentTimeMillis();
        try {
            SpringApplication app = new SpringApplicationBuilder(SuperAutoApplication.class).build(args);
            // 主上下文 refresh 前做 CORS / 数据源自检，此时 application.yml 已加载
            app.addInitializers(new StartupContextInitializer());
            Environment env = app.run(args).getEnvironment();

            String protocol = "http";
            if (env.getProperty("server.ssl.key-store") != null) {
                protocol = "https";
            }

            String appName = env.getProperty("spring.application.name");
            String configuredPort = env.getProperty("server.port", "3030");
            String ip = InetAddress.getLocalHost().getHostAddress();
            String[] profiles = env.getActiveProfiles();

            // ANSI 颜色定义
            String GREEN = "\033[1;32m";
            String CYAN = "\033[1;36m";
            String YELLOW = "\033[1;33m";
            String RESET = "\033[0m";

            log.info("\n" +
                            "---------------------------------------------------------------------------------------\n" +
                            "  🚀  {}Application '{}' is running!{}\n\n" +
                            "  🔗  {}Local:      {}://localhost:{}\n" +
                            "  🌐  {}External:   {}://{}:{}\n" +
                            "  📦  {}Profile(s): {}\n" +
                            "  📚  {}Study Policy: {}\n" +
                            "---------------------------------------------------------------------------------------\n",
                    GREEN, appName, RESET,
                    CYAN, protocol, configuredPort,
                    CYAN, protocol, ip, configuredPort,
                    YELLOW, String.join(", ", profiles),
                    YELLOW, com.tihai.constant.StudyPolicy.describe()
            );

            int port = Integer.parseInt(configuredPort);
            StartupPhaseLog.pass(StartupPhase.WEB, start, "port=" + port);
            StartupReadinessMarker.markReady(port, standalone);
        } catch (Throwable throwable) {
            StartupFailureReporter.reportUncaught(throwable, StartupPhase.FAIL);
            System.exit(1);
        }
    }
}