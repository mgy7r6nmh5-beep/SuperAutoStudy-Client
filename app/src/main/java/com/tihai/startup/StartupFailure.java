package com.tihai.startup;

/**
 * 启动失败描述。字段固定为六要素 + 脱敏技术摘要，构造后不可变。
 */
public final class StartupFailure {

    private final String time;
    private final StartupPhase phase;
    private final StartupErrorCode code;
    private final String reason;
    private final String next;
    private final String logPath;
    private final String technicalDigest;

    public StartupFailure(String time, StartupPhase phase, StartupErrorCode code,
                          String reason, String next, String logPath, String technicalDigest) {
        this.time = time;
        this.phase = phase;
        this.code = code;
        this.reason = StartupFailureReporter.redact(reason);
        this.next = StartupFailureReporter.redact(next);
        this.logPath = logPath;
        this.technicalDigest = StartupFailureReporter.redact(technicalDigest);
    }

    public String getTime() {
        return time;
    }

    public StartupPhase getPhase() {
        return phase;
    }

    public StartupErrorCode getCode() {
        return code;
    }

    public String getReason() {
        return reason;
    }

    public String getNext() {
        return next;
    }

    public String getLogPath() {
        return logPath;
    }

    public String getTechnicalDigest() {
        return technicalDigest;
    }
}