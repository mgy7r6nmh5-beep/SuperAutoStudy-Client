package com.tihai.controller;

import com.tihai.common.R;
import com.tihai.service.quiz.QuizService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 章节测验的半自动接口。
 *
 * <p>流程：刷课线程抓到测验 → 问 AI → 落库（status=1 待确认）→ 界面拉列表
 * → 用户看一眼、可能改答案 → 调 submit → 服务端提交给超星。
 *
 * <p>刻意没有「一键全部自动提交」的接口：一次提交就定分，错题没有后悔药。
 */
@Slf4j
@RestController
@RequestMapping("/api/quiz")
public class QuizController {

    private final QuizService quizService;

    public QuizController(QuizService quizService) {
        this.quizService = quizService;
    }

    /**
     * 待确认列表。
     *
     * @return data = [{id, course_name, title, status, error_message, create_time,
     *                  questionCount, questions:[{id,type,title,options,suggestion,
     *                  confidence,reason,answer,manual,note}]}]
     */
    @GetMapping("")
    public R list(@RequestParam("account") String account,
                  @RequestParam(value = "limit", required = false, defaultValue = "30") int limit) {
        List<Map<String, Object>> rows = quizService.list(account, Math.min(limit, 100));
        return R.ok().setData(rows);
    }

    /** 待确认条数，总览页拿来做小红点。 */
    @GetMapping("/count")
    public R count(@RequestParam("account") String account) {
        return R.ok().setData(quizService.pendingCount(account));
    }

    /**
     * 提交。
     *
     * <p>请求体：{@code {"answers": {"405548275": "A", "405548276": "ABC"}}}
     * —— 只传用户改过的题就行，没传的沿用 AI 建议。
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/{id}/submit")
    public R submit(@PathVariable("id") long id,
                    @RequestBody(required = false) Map<String, Object> body) {
        Map<String, String> answers = null;
        if (body != null && body.get("answers") instanceof Map) {
            answers = (Map<String, String>) body.get("answers");
        }
        Map<String, Object> r = quizService.submit(id, answers);
        return Boolean.TRUE.equals(r.get("ok"))
                ? R.ok().setData(r)
                : R.error(400, String.valueOf(r.get("message"))).setData(r);
    }

    /** 跳过这一份，不再处理。 */
    @PostMapping("/{id}/skip")
    public R skip(@PathVariable("id") long id,
                  @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null ? null : String.valueOf(body.get("reason"));
        quizService.skip(id, reason);
        return R.ok().setData("已跳过");
    }

    /**
     * 让 AI 重新作答。
     *
     * <p>抓题那一刻网络断了、或者模型抽风只答了一半，就点这个重来一次，
     * 不用手填。
     */
    @PostMapping("/{id}/retry")
    public R retry(@PathVariable("id") long id) {
        Map<String, Object> r = quizService.retry(id);
        return Boolean.TRUE.equals(r.get("ok"))
                ? R.ok().setData(r)
                : R.error(400, String.valueOf(r.get("message"))).setData(r);
    }

    /** 重新查成绩（交过之后才有的读）。 */
    @PostMapping("/{id}/score")
    public R score(@PathVariable("id") long id) {
        Map<String, Object> r = quizService.refreshScore(id);
        return Boolean.TRUE.equals(r.get("ok"))
                ? R.ok().setData(r)
                : R.error(400, String.valueOf(r.get("message"))).setData(r);
    }

    /** 最近交过的几份 + 成绩。 */
    @GetMapping("/recent")
    public R recent(@RequestParam("account") String account,
                    @RequestParam(value = "limit", required = false, defaultValue = "10") int limit) {
        return R.ok().setData(quizService.recent(account, Math.min(limit, 50)));
    }
}
