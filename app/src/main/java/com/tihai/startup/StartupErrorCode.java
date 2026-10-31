package com.tihai.startup;

/**
 * 启动失败稳定错误码。一经发布不复用，客户端按此展示。
 */
public enum StartupErrorCode {
    CONFIG("STARTUP-CONFIG-001"),
    CORS("STARTUP-CORS-001"),
    DB("STARTUP-DB-001"),
    PORT("STARTUP-PORT-001"),
    REGISTRY("STARTUP-REGISTRY-001"),
    INTERNAL("STARTUP-INTERNAL-001"),
    JDK("STARTUP-JDK-001"),
    JAR("STARTUP-JAR-001"),
    TIMEOUT("STARTUP-TIMEOUT-001"),
    LOG("STARTUP-LOG-001");

    private final String code;

    StartupErrorCode(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}