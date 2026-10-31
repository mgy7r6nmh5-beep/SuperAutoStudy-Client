package com.tihai.constant;

/**
 * 章节测验的处理策略。
 *
 * <p>用户可以在「设置 → AI 作答」里选，值存在 {@code app/ai-config.json} 的
 * {@code answer_mode} 里，每次抓题前重读，改完不用重启后端。
 *
 * <p>为什么分成五档而不是一个「自动提交」开关：真正决定风险的不是「自不自动」，
 * 而是「多没把握才肯交」。把门槛摆出来让人自己选，比替他做决定清楚。
 */
public final class QuizMode {

    private QuizMode() {
    }

    /** 关掉答题：遇到 workid 直接跳过，和 v1.0.8 之前一样。 */
    public static final String OFF = "off";

    /** 全部挂「待确认」，人看过才交。默认。 */
    public static final String CONFIRM = "confirm";

    /** 每道题都过了校验、且置信度全是 high，才自动交。 */
    public static final String AUTO_HIGH = "auto_high";

    /** 置信度都不低于 medium 就自动交。 */
    public static final String AUTO_MEDIUM = "auto_medium";

    /** 只要能答出来就自动交（含 low）。最容易交错的档，只在完全不想管时用。 */
    public static final String AUTO_ANY = "auto_any";

    public static boolean valid(String mode) {
        return OFF.equals(mode) || CONFIRM.equals(mode)
                || AUTO_HIGH.equals(mode) || AUTO_MEDIUM.equals(mode) || AUTO_ANY.equals(mode);
    }

    public static boolean isOff(String mode) {
        return OFF.equals(mode);
    }

    /** 这一档要不要自动提交。 */
    public static boolean auto(String mode) {
        return AUTO_HIGH.equals(mode) || AUTO_MEDIUM.equals(mode) || AUTO_ANY.equals(mode);
    }

    /**
     * 自动提交要求的最低置信度档位：high=3 / medium=2 / low=1。
     *
     * <p>不自动交的档返回 4 —— 比任何一档都高，调用方拿去比较自然一个都过不了，
     * 不用再单独判断模式。
     */
    public static int minConfidenceRank(String mode) {
        if (AUTO_HIGH.equals(mode)) {
            return 3;
        }
        if (AUTO_MEDIUM.equals(mode)) {
            return 2;
        }
        if (AUTO_ANY.equals(mode)) {
            return 1;
        }
        return 4;
    }

    /** 给日志和界面用的人话。 */
    public static String label(String mode) {
        if (OFF.equals(mode)) {
            return "关闭答题";
        }
        if (AUTO_HIGH.equals(mode)) {
            return "自动提交（全 high）";
        }
        if (AUTO_MEDIUM.equals(mode)) {
            return "自动提交（≥ medium）";
        }
        if (AUTO_ANY.equals(mode)) {
            return "自动提交（能答就交）";
        }
        return "全部人工确认";
    }
}
