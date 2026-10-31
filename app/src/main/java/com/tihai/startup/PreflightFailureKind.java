package com.tihai.startup;

/**
 * 数据源故障分类，决定失败原因与下一步提示。
 */
public enum PreflightFailureKind {
    UNREACHABLE,
    AUTH_FAILED,
    DATABASE_MISSING,
    CONFIG_MISSING,
    UNKNOWN
}