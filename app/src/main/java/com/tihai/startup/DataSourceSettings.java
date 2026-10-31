package com.tihai.startup;

/**
 * 数据源预检所需的最小配置视图，禁止在日志中输出密码。
 */
public final class DataSourceSettings {

    private final String url;
    private final String username;
    private final String password;
    private final int timeoutSeconds;

    public DataSourceSettings(String url, String username, String password, int timeoutSeconds) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.timeoutSeconds = Math.max(1, Math.min(10, timeoutSeconds));
    }

    public String getUrl() {
        return url;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    /**
     * 只输出 host:port/db，用于日志与失败块。
     */
    public String safeEndpoint() {
        if (url == null) {
            return "(未配置)";
        }
        String value = url;
        int scheme = value.indexOf("//");
        if (scheme >= 0) {
            value = value.substring(scheme + 2);
        }
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        return value;
    }

    @Override
    public String toString() {
        return "DataSourceSettings{endpoint=" + safeEndpoint() + ", timeout=" + timeoutSeconds + "s}";
    }
}