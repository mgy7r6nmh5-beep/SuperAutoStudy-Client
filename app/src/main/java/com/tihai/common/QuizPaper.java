package com.tihai.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一份章节测验：提交要用的表单字段 + 题目列表。
 *
 * <p>超星提交作业是把整个 form 原样 POST 回去，只把 {@code answer{id}} 和
 * {@code answertype{id}} 换成实际值。所以抓题时必须把页面 form 里**除了
 * answer* 以外**的所有 input 都存下来，少一个字段服务端就拒。
 */
@Data
@NoArgsConstructor
public class QuizPaper {

    private String courseId;
    private String courseName;
    private String clazzId;
    private String cpi;
    private String knowledgeId;

    /** 形如 {@code work-2d4ca13ec9c34b109cd0e8d97588fd30}。 */
    private String jobId;

    /** 去掉 work- 前缀的纯 id，提交时用。 */
    private String workId;

    private String title;

    /**
     * 提交地址，直接从页面 {@code <form action="...">} 抄。
     *
     * <p>别硬编码成 {@code /mooc-ans/work/addStudentWorkNewWeb}：action 里带着
     * {@code token}、{@code enc_work}、{@code workAnswerId}、{@code totalQuestionNum}
     * 这些每次会话都变的参数，少了必然被服务端拒。
     *
     * <p>而且这个值通常是**裸相对路径**（{@code addStudentWorkNewWeb?...}），
     * 页面里也没有 {@code <base>}，得靠 {@link #pageUrl} 才能解析成绝对地址。
     */
    private String submitAction;

    /**
     * 抓题时那次请求的**最终**页面地址（跟完 302 之后的）。
     *
     * <p>{@code api/work?needRedirect=true} 会跳到 {@code /mooc-ans/work/doHomeWorkNew}，
     * 相对的表单 action 就落在 {@code /mooc-ans/work/} 下。少了这个值，
     * 无论拼 {@code /mooc-ans/} 还是 {@code /mooc-ans/api/} 都是 404。
     */
    private String pageUrl;

    /**
     * 这一份是不是**已经交过、现在拿到的只是批阅页**。
     *
     * <p>交过之后同一个 workId 再请求，超星不再给做题页，直接跳到
     * {@code selectWorkQuestionYiPiYue}（「我的答案 / 正确答案」那页）。
     * 那页里也有 {@code div.singleQuesId}，能解析出题目，但：
     * <ul>
     *   <li>题型全是 unknown（批阅页没有 {@code TiMu[data]} 那个题型码）</li>
     *   <li>页面上第一个 {@code <form>} 是「题目纠错」的，
     *       action 指向 {@code fanyapbl.fy.chaoxing.com/questionError/addQuestion}</li>
     * </ul>
     * 不认出来就会把一份已完成的卷子重新挂到「待确认」，点提交还会往纠错接口发。
     */
    private boolean alreadySubmitted;

    /** 页面 form 里的隐藏字段（不含 answer*）。提交时原样带上。 */
    private Map<String, String> form = new LinkedHashMap<>();

    private List<QuizQuestion> questions;

    /**
     * 提交后从批阅页读到的「本次成绩」。null = 还没查过 / 查不到。
     * 存在 paper_json 里，不占新字段，老库不用改表。
     */
    private Double score;

    /** 批阅页上的「满分」。 */
    private Double fullScore;

    /** 这个分数是什么时候读到的（ISO 字符串，界面上显示用）。 */
    private String scoreAt;

    /** 抓到几道题。0 通常意味着选择器过期或者被返回了提示页。 */
    public int size() {
        return questions == null ? 0 : questions.size();
    }
}
