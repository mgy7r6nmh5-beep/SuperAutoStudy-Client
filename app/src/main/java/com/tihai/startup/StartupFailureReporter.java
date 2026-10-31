package com.tihai.startup;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 启动失败上报：同时写控制台与 {@code <root>/app/live.log}。
 *
 * <p>不依赖 Spring 容器与日志框架，保证容器创建失败时仍能输出。
 * 失败块按"错误码 + 阶段 + 进程启动时间"去重，同一进程只输出一次。
 */
public final class StartupFailureReporter {

    private static final Set<String> REPORTED = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());
    private static final long PROCESS_START = System.currentTimeMillis();

    private static final Pattern SECRET = Pattern.compile(
            "(?i)(password|pwd|token|authorization|cookie)\\s*[=:]\\s*[^,;\\s]+");
    private static final Pattern JDBC = Pattern.compile(
            "jdbc:mysql://([^/\\s]+)/([^?\\s]+)\\?[^\\s]*");

    private StartupFailureReporter() {
    }

    public static String logPath() {
        String configured = System.getProperty("superauto.log.file");
        if (configured != null && !configured.trim().isEmpty()) {
            return configured;
        }
        return resolveLogPath();
    }

    /**
     * 优先在部署根（含 mysql57 或 client+app）下写 app/live.log；否则退回 cwd。
     */
    public static String resolveLogPath() {
        File cursor = new File(System.getProperty("user.dir")).getAbsoluteFile();
        File root = null;
        while (cursor != null) {
            if (new File(cursor, "mysql57").isDirectory()
                    || (new File(cursor, "client").isDirectory() && new File(cursor, "app").isDirectory())) {
                root = cursor;
                break;
            }
            cursor = cursor.getParentFile();
        }
        File base = root != null ? root : new File(System.getProperty("user.dir")).getAbsoluteFile();
        return new File(new File(base, "app"), "live.log").getAbsolutePath();
    }

    public static void report(StartupFailure failure) {
        String key = failure.getCode().getCode() + "|" + failure.getPhase().name() + "|" + PROCESS_START;
        if (!REPORTED.add(key)) {
            return;
        }
        String block = render(failure);
        System.err.println(block);
        append(block);
    }

    public static void reportUncaught(Throwable throwable, StartupPhase phase) {
        StartupErrorCode code = classify(throwable);
        StartupFailure failure = new StartupFailure(
                now(), phase, code, reasonOf(code), nextOf(code), logPath(), digest(throwable));
        report(failure);
    }

    public static String redact(String text) {
        if (text == null) {
            return "";
        }
        String masked = SECRET.matcher(text).replaceAll("$1=***");
        Matcher jdbc = JDBC.matcher(masked);
        return jdbc.replaceAll("jdbc:mysql://$1/$2");
    }

    static StartupErrorCode classify(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof CorsConfigException) {
                return StartupErrorCode.CORS;
            }
            String name = cursor.getClass().getName();
            String message = cursor.getMessage() == null ? "" : cursor.getMessage().toLowerCase();
            if (name.contains("BindException") || (message.contains("port") && message.contains("in use"))) {
                return StartupErrorCode.PORT;
            }
            if (message.contains("could not resolve placeholder")
                    || message.contains("failed to bind properties")) {
                return StartupErrorCode.CONFIG;
            }
            cursor = cursor.getCause();
        }
        return StartupErrorCode.INTERNAL;
    }

    private static String reasonOf(StartupErrorCode code) {
        switch (code) {
            case CORS:
                return "跨域配置非法";
            case PORT:
                return "端口 3030 已被占用";
            case CONFIG:
                return "启动配置缺失或无法绑定";
            default:
                return "后端启动失败";
        }
    }

    private static String nextOf(StartupErrorCode code) {
        switch (code) {
            case CORS:
                return "检查 cors.allow.origins：只允许 http(s)://主机[:端口]，且不能使用 *";
            case PORT:
                return "关闭占用 3030 的程序后重试，或使用 --server.port 换端口";
            case CONFIG:
                return "检查 app/config/application.yml 与环境变量是否完整";
            default:
                return "查看日志中的首个 Caused by，确认 MySQL 与配置后重试";
        }
    }

    private static String digest(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return redact(root.getClass().getSimpleName() + ": " + root.getMessage());
    }

    private static String render(StartupFailure failure) {
        return "==== STARTUP FAILED ====\n"
                + "time=" + failure.getTime() + "\n"
                + "phase=" + failure.getPhase().name().toLowerCase() + "\n"
                + "code=" + failure.getCode().getCode() + "\n"
                + "reason=" + failure.getReason() + "\n"
                + "next=" + failure.getNext() + "\n"
                + "logPath=" + failure.getLogPath() + "\n"
                + "detail=" + failure.getTechnicalDigest() + "\n"
                + "==== END STARTUP FAILED ====";
    }

    /**
     * 直接追加一行到启动日志。供日志框架初始化前的阶段行使用。
     */
    public static void appendLine(String line) {
        if (line == null) {
            return;
        }
        append(line);
    }

    private static void append(String block) {
        Path path = Paths.get(logPath());
        try {
            File parent = path.toFile().getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return;
            }
            Files.write(path, (block + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignore) {
            // 日志不可写时降级为仅控制台；不递归上报，避免二次失败
        }
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }
}