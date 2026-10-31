package com.tihai.startup;

/**
 * 数据源预检结果。
 */
public final class PreflightResult {

    private final boolean passed;
    private final String reason;
    private final String next;
    private final StartupErrorCode errorCode;
    private final long elapsedMs;
    private final PreflightFailureKind kind;

    private PreflightResult(boolean passed, String reason, String next,
                            StartupErrorCode errorCode, long elapsedMs, PreflightFailureKind kind) {
        this.passed = passed;
        this.reason = reason;
        this.next = next;
        this.errorCode = errorCode;
        this.elapsedMs = elapsedMs;
        this.kind = kind;
    }

    public static PreflightResult pass(long elapsedMs) {
        return new PreflightResult(true, "", "", null, elapsedMs, null);
    }

    public static PreflightResult fail(PreflightFailureKind kind, String reason, String next, long elapsedMs) {
        return new PreflightResult(false, reason, next, StartupErrorCode.DB, elapsedMs, kind);
    }

    public boolean isPassed() {
        return passed;
    }

    public String getReason() {
        return reason;
    }

    public String getNext() {
        return next;
    }

    public StartupErrorCode getErrorCode() {
        return errorCode;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public PreflightFailureKind getKind() {
        return kind;
    }
}