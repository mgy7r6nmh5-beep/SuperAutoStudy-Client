package com.tihai.startup;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 数据源预检：只读 {@code SELECT 1}，不触碰任何业务表。
 *
 * <p>在 Web 容器绑定 3030 之前给出结论，避免"端口已监听但服务不可用"的假就绪。
 */
public final class DataSourcePreflight {

    private final DataSourceSettings settings;

    public DataSourcePreflight(DataSourceSettings settings) {
        this.settings = settings;
    }

    public PreflightResult check() {
        long start = System.currentTimeMillis();
        if (settings.getUrl() == null || settings.getUrl().trim().isEmpty()) {
            return PreflightResult.fail(PreflightFailureKind.CONFIG_MISSING,
                    "数据源配置缺失", "检查 spring.datasource.url 或 DB_URL", elapsed(start));
        }
        try {
            DriverManager.setLoginTimeout(settings.getTimeoutSeconds());
            try (Connection connection = DriverManager.getConnection(
                    settings.getUrl(), settings.getUsername(), settings.getPassword());
                 Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(settings.getTimeoutSeconds());
                statement.execute("SELECT 1");
            }
            return PreflightResult.pass(elapsed(start));
        } catch (SQLException e) {
            PreflightFailureKind kind = classify(e);
            return PreflightResult.fail(kind, reasonOf(kind), nextOf(kind), elapsed(start));
        }
    }

    static PreflightFailureKind classify(SQLException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        if (state.startsWith("28") || message.contains("access denied")
                || message.contains("using password")) {
            return PreflightFailureKind.AUTH_FAILED;
        }
        if (message.contains("unknown database") || message.contains("database") && message.contains("does not exist")) {
            return PreflightFailureKind.DATABASE_MISSING;
        }
        if (message.contains("connection refused") || message.contains("communications link failure")
                || message.contains("connect timed out") || message.contains("unknownhost")) {
            return PreflightFailureKind.UNREACHABLE;
        }
        return PreflightFailureKind.UNKNOWN;
    }

    static String reasonOf(PreflightFailureKind kind) {
        switch (kind) {
            case AUTH_FAILED:
                return "数据库账号不可用";
            case DATABASE_MISSING:
                return "业务库不存在";
            case CONFIG_MISSING:
                return "数据源配置缺失";
            case UNREACHABLE:
                return "本地数据库未就绪";
            default:
                return "数据库预检失败";
        }
    }

    static String nextOf(PreflightFailureKind kind) {
        switch (kind) {
            case AUTH_FAILED:
                return "检查 DB_USER / DB_PASSWORD 与 MySQL 实际账号是否一致";
            case DATABASE_MISSING:
                return "确认已创建 tihai 数据库";
            case CONFIG_MISSING:
                return "检查 spring.datasource.* 或 DB_URL 环境变量";
            case UNREACHABLE:
                return "先启动 MySQL（确认 127.0.0.1:3306 正在监听）";
            default:
                return "查看日志中的 SQLException 详情后重试";
        }
    }

    private static long elapsed(long start) {
        return Math.max(0L, System.currentTimeMillis() - start);
    }
}