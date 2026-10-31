package com.tihai.startup;

/**
 * 启动阶段，用于日志行与失败块。
 */
public enum StartupPhase {
    CONFIG,
    CORS,
    DATASOURCE,
    WEB,
    READY,
    FAIL
}