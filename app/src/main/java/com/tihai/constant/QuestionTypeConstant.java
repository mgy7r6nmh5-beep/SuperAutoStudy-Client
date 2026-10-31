package com.tihai.constant;

/**
 * 超星题型码。
 *
 * <p>页面里 {@code <div class="TiMu newTiMu" data="0">} 的 {@code data} 就是这组值，
 * 而且同一个值已经写在隐藏字段 {@code answertype{题目id}} 里了 —— 提交时直接
 * 从页面读，不用自己再映射一遍。
 *
 * <p>旧实现自己维护了一张 type 字符串 → 码的表，但 {@code Decode} 对填空产出
 * {@code "completion"}、{@code processAnswers} 却判 {@code "fill"}，两边对不上，
 * 填空题永远走随机。这里统一成一份，别再有第二处映射。
 */
public final class QuestionTypeConstant {

    /** 单选。答案形如 "A"。 */
    public static final int SINGLE_CHOICE = 0;

    /** 多选。答案形如 "ACD"，字母升序、无分隔符。 */
    public static final int MULTIPLE_CHOICE = 1;

    /** 填空。答案形如 "xx|||yy"，多空用三个竖线隔开。 */
    public static final int FILL_IN = 2;

    /** 判断。答案只有 "true" / "false"。 */
    public static final int JUDGMENT = 3;

    /** 简答。答案是一段文字。 */
    public static final int SHORT_ANSWER = 4;

    /** 页面 data 值 → 给 AI 看的题型名。 */
    public static String nameOf(int code) {
        switch (code) {
            case SINGLE_CHOICE:
                return "single";
            case MULTIPLE_CHOICE:
                return "multiple";
            case FILL_IN:
                return "fill";
            case JUDGMENT:
                return "judge";
            case SHORT_ANSWER:
                return "short";
            default:
                return "unknown";
        }
    }

    private QuestionTypeConstant() {
    }
}
