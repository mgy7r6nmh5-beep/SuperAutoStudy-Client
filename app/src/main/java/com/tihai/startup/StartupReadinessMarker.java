package com.tihai.startup;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 就绪声明。全进程只输出一次，避免客户端重复读到多条 ready。
 */
public final class StartupReadinessMarker {

    private static final AtomicBoolean PRINTED = new AtomicBoolean(false);

    private StartupReadinessMarker() {
    }

    public static void markReady(int port, StandaloneModeState state) {
        if (!PRINTED.compareAndSet(false, true)) {
            return;
        }
        String declaration = "[STARTUP] " + state.describe();
        String ready = "[STARTUP] phase=ready result=PASS url=http://127.0.0.1:" + port;
        System.out.println(declaration);
        System.out.println(ready);
        StartupFailureReporter.appendLine(declaration);
        StartupFailureReporter.appendLine(ready);
    }
}