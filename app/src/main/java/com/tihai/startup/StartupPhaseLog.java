package com.tihai.startup;

/**
 * 阶段日志行：[STARTUP] phase=cors result=PASS elapsedMs=4 detail
 */
public final class StartupPhaseLog {

    private StartupPhaseLog() {
    }

    public static void pass(StartupPhase phase, long startMs, String detail) {
        print(phase, "PASS", startMs, detail);
    }

    public static void fail(StartupPhase phase, long startMs, String detail) {
        print(phase, "FAIL", startMs, detail);
    }

    private static void print(StartupPhase phase, String result, long startMs, String detail) {
        long elapsed = Math.max(0L, System.currentTimeMillis() - startMs);
        StringBuilder line = new StringBuilder()
                .append("[STARTUP] phase=").append(phase.name().toLowerCase())
                .append(" result=").append(result)
                .append(" elapsedMs=").append(elapsed);
        if (detail != null && !detail.trim().isEmpty()) {
            line.append(' ').append(detail);
        }
        System.out.println(line);
        // 日志框架初始化前的阶段行也要落盘，保证 live.log 覆盖 cors/datasource
        StartupFailureReporter.appendLine(line.toString());
    }
}