package com.tihai.constant;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 固定运行策略。
 *
 * <p>刷课行为不再由配置文件或客户端命令行参数决定，统一以本类常量为唯一来源：
 * 单课串行、真实播放节奏、断点续刷默认开启、自动重试上限固定。
 * 这里不提供 setter，也不绑定任何 {@code @ConfigurationProperties}。
 */
public final class StudyPolicy {

    /** 任意时刻只允许一个学习线程，即一门课、一个任务点。 */
    public static final int LEARNING_THREADS = 1;

    /** 播放进度上报步长（秒）。cards 页下发 reportTimeInterval 时优先用下发的值。 */
    public static final int DEFAULT_STEP_SECONDS = 60;

    /** 单个视频最多完整播放几遍。 */
    public static final int MAX_ROUNDS = 2;

    /** 断点续刷默认开启且不可关闭。 */
    public static final boolean RESUME_ENABLED = true;

    /** 自动重试上限（轮）。达到后不再自动入队，需用户手动续刷。 */
    public static final int MAX_AUTO_RETRY = 5;

    /** 优先级取值范围，1 为最高。 */
    public static final int MIN_PRIORITY = 1;
    public static final int MAX_PRIORITY = 10;

    /** 无条件跳过的任务点类型（做题 / 章节测验 / 作业）。 */
    public static final Set<String> SKIP_JOB_TYPES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Collections.singletonList(JobTypeConstant.QUESTION)));

    private StudyPolicy() {
    }

    /**
     * 单行可读摘要，启动时打到日志里。
     */
    public static String describe() {
        return "单课串行 / 真实节奏 / 断点续刷开启 / 步长 " + DEFAULT_STEP_SECONDS
                + "s / 最多 " + MAX_ROUNDS + " 遍 / 自动重试上限 " + MAX_AUTO_RETRY + " 轮";
    }
}