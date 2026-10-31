package com.tihai.startup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 单机模式守卫。
 *
 * <p>本地部署只需要 HTTP + MySQL，不能因为找不到 Nacos 而反复重试 8848/9848，
 * 也不应该在单机上导出 Dubbo RPC 端口。本类不依赖 Spring，在
 * {@code SpringApplication} 创建之前写入系统属性，确保 bootstrap 上下文
 * 也能读到关闭开关。
 *
 * <p>放开中间件只允许命令行白名单，遗留的 yml / 环境变量无法反向生效。
 */
public final class StandaloneModeGuard {

    public static final String STANDALONE = "superauto.standalone.enabled";
    public static final String NACOS_CONFIG = "superauto.middleware.nacos-config-enabled";
    public static final String NACOS_DISCOVERY = "superauto.middleware.nacos-discovery-enabled";
    public static final String DUBBO_EXPORT = "superauto.middleware.dubbo-export-enabled";

    private static volatile StandaloneModeState current;

    private StandaloneModeGuard() {
    }

    public static StandaloneModeState apply(String[] args) {
        Map<String, String> cli = parse(args);
        boolean standalone = !"false".equalsIgnoreCase(cli.get(STANDALONE));

        boolean nacosConfig = false;
        boolean nacosDiscovery = false;
        boolean dubboExport = false;

        if (standalone) {
            // 强制关闭：bootstrap、Nacos 配置、Nacos 发现、Spring Cloud 发现、Dubbo 注册
            System.setProperty("spring.cloud.bootstrap.enabled", "false");
            System.setProperty("spring.cloud.nacos.config.enabled", "false");
            System.setProperty("spring.cloud.nacos.discovery.enabled", "false");
            System.setProperty("spring.cloud.discovery.enabled", "false");
            System.setProperty("dubbo.registry.address", "N/A");
            System.setProperty("enable-service-registry", "false");
        } else {
            // 显式非单机：不写任何强制项，只读取命令行白名单
            nacosConfig = "true".equalsIgnoreCase(cli.get(NACOS_CONFIG));
            nacosDiscovery = "true".equalsIgnoreCase(cli.get(NACOS_DISCOVERY));
            dubboExport = "true".equalsIgnoreCase(cli.get(DUBBO_EXPORT));
        }

        StandaloneModeState state = new StandaloneModeState(
                standalone, nacosConfig, nacosDiscovery, dubboExport, scanLegacyKeys());
        current = state;
        return state;
    }

    public static StandaloneModeState current() {
        StandaloneModeState state = current;
        return state != null ? state : new StandaloneModeState(true, false, false, false, new ArrayList<>());
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> parsed = new HashMap<>();
        if (args == null) {
            return parsed;
        }
        for (String arg : args) {
            if (arg == null || !arg.startsWith("--")) {
                continue;
            }
            String body = arg.substring(2);
            int eq = body.indexOf('=');
            if (eq < 0) {
                parsed.put(body, "true");
            } else {
                parsed.put(body.substring(0, eq), body.substring(eq + 1));
            }
        }
        return parsed;
    }

    /**
     * 只记录键名，不记录值，避免把中间件地址、账号带进日志。
     */
    private static List<String> scanLegacyKeys() {
        List<String> keys = new ArrayList<>();
        for (String key : System.getenv().keySet()) {
            String upper = key.toUpperCase(Locale.ROOT);
            if (upper.startsWith("SPRING_CLOUD_NACOS_") || upper.startsWith("DUBBO_")) {
                keys.add(key);
            }
        }
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith("spring.cloud.nacos.") || key.startsWith("dubbo.")) {
                keys.add(key);
            }
        }
        return keys;
    }
}