package com.tihai.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 一道题（题干 + AI 建议 + 用户最终答案）。
 *
 * <p>半自动流程下这三个字段是分开的：{@code suggestion} 是 AI 给的，
 * {@code answer} 是真正会提交的（默认等于 suggestion，用户可以在界面上改）。
 * 提交前一定要看 {@code answer}，别直接拿 suggestion 交 —— 那是没经过人工确认的。
 */
@Data
@NoArgsConstructor
public class QuizQuestion {

    /** 题目 id，页面 {@code <div class="singleQuesId" data="405548275">} 里的值。 */
    private String id;

    /** 题型码，见 {@link com.tihai.constant.QuestionTypeConstant}。 */
    private int answerType;

    /** 题型名（single / multiple / fill / judge / short），只给 AI 和界面看。 */
    private String type;

    /** 题干，已经过字体解码。 */
    private String title;

    /** 选项，形如 {@code ["A. 16", "B. 32", "C. 48", "D. 72"]}。判断/填空题为空。 */
    private List<String> options = new ArrayList<>();

    /** AI 建议答案。 */
    private String suggestion = "";

    /** high / medium / low，AI 自评。 */
    private String confidence = "";

    /** AI 给的依据，不超过 40 字。 */
    private String reason = "";

    /** 最终要提交的答案，默认跟随 suggestion。 */
    private String answer = "";

    /** AI 答案没过本地校验，必须人工填。 */
    private boolean manual;

    /** 校验/重试留下的说明，给界面显示。 */
    private String note = "";
}
