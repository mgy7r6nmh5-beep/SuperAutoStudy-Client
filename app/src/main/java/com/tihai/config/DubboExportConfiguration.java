package com.tihai.config;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Dubbo 导出开关。
 *
 * <p>默认不装配：单机部署不需要注册中心，也不应该监听 RPC 端口。
 * 只有显式传入 {@code --superauto.standalone.enabled=false
 * --superauto.middleware.dubbo-export-enabled=true} 时才启用。
 */
@Configuration
@ConditionalOnProperty(prefix = "superauto.middleware", name = "dubbo-export-enabled",
        havingValue = "true")
@EnableDubbo
public class DubboExportConfiguration {
}