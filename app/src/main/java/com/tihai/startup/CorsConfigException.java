package com.tihai.startup;

/**
 * 跨域配置非法时抛出，携带稳定错误码，供启动失败上报统一消费。
 */
public class CorsConfigException extends RuntimeException {

    public static final String ERROR_CODE = "STARTUP-CORS-001";

    public CorsConfigException(String message) {
        super(message);
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }
}