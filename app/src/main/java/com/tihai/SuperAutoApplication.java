package com.tihai;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
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
@EnableDubbo
@EnableDiscoveryClient
@MapperScan("com.tihai.mapper")
@EnableScheduling
@Slf4j
public class SuperAutoApplication {
    public static void main(String[] args) throws UnknownHostException {
        SpringApplication app = new SpringApplicationBuilder(SuperAutoApplication.class).build(args);
        Environment env = app.run(args).getEnvironment();
        String protocol = "http";
        if (env.getProperty("server.ssl.key-store") != null) {
            protocol = "https";
        }

        String appName = env.getProperty("spring.application.name");
        String port = env.getProperty("server.port");
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
                CYAN, protocol, port,
                CYAN, protocol, ip, port,
                YELLOW, String.join(", ", profiles),
                YELLOW, com.tihai.constant.StudyPolicy.describe()
        );
    }

}

