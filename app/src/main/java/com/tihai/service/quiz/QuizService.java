package com.tihai.service.quiz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tihai.common.Job;
import com.tihai.common.JobInfo;
import com.tihai.common.QuizPaper;
import com.tihai.common.QuizQuestion;
import com.tihai.constant.QuizMode;
import com.tihai.dubbo.pojo.course.Course;
import com.tihai.service.ai.AiAnswerService;
import com.tihai.utils.CourseUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 章节测验的「抓题 → AI 出答案 → 等人工确认 → 提交」流程。
 *
 * <p>为什么不做全自动：刷课线程是单线程串行的，它不能停下来等用户点确认，
 * 一停整个队列就卡住。所以遇到 workid 时只做「抓题 + 问 AI + 落库」，
 * 然后把状态标成待确认，**继续刷下一章**。用户什么时候方便什么时候处理。
 *
 * <p>存储用 JdbcTemplate 直接读写，不引 MyBatis mapper —— 就一张表、
 * 四个操作，走 mapper 反而更绕。表在启动时 {@code CREATE TABLE IF NOT EXISTS}
 * 自动建，老用户升级不用手工执行 SQL。
 */
@Slf4j
@Service
public class QuizService {

    /** 待人工确认。 */
    public static final int STATUS_PENDING = 1;
    /** 已提交成功。 */
    public static final int STATUS_SUBMITTED = 2;
    /** 提交失败（服务端拒了）。 */
    public static final int STATUS_FAILED = 3;
    /** 用户跳过，不再处理。 */
    public static final int STATUS_SKIPPED = 4;
    /** 抓题或 AI 阶段就失败了，连卷子都没成形。 */
    public static final int STATUS_BROKEN = 5;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final CourseUtil courseUtil;
    private final AiAnswerService aiAnswerService;

    public QuizService(DataSource dataSource, CourseUtil courseUtil,
                       AiAnswerService aiAnswerService) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.courseUtil = courseUtil;
        this.aiAnswerService = aiAnswerService;
    }

    @PostConstruct
    public void ensureTable() {
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS th_wk_quiz ("
                    + "  id BIGINT NOT NULL AUTO_INCREMENT,"
                    + "  account VARCHAR(64) DEFAULT NULL,"
                    + "  course_id VARCHAR(64) DEFAULT NULL,"
                    + "  course_name VARCHAR(255) DEFAULT NULL,"
                    + "  job_id VARCHAR(96) DEFAULT NULL,"
                    + "  title VARCHAR(255) DEFAULT NULL,"
                    + "  paper_json LONGTEXT,"
                    + "  status INT NOT NULL DEFAULT 1,"
                    + "  error_message VARCHAR(512) DEFAULT NULL,"
                    + "  create_time DATETIME DEFAULT NULL,"
                    + "  update_time DATETIME DEFAULT NULL,"
                    + "  PRIMARY KEY (id),"
                    + "  KEY idx_account_status (account, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            log.info("th_wk_quiz 就绪");
        } catch (Exception e) {
            log.warn("建 th_wk_quiz 失败（做题功能会不可用）: {}", e.toString());
        }
    }

    /**
     * 刷课线程调用：抓题 + 问 AI + 落库，返回新记录 id。
     *
     * <p>整个过程不抛异常 —— 做题失败不能把整门课的刷课带崩。
     * 出问题就落一条 {@link #STATUS_BROKEN} 记录，让用户在界面上看到。
     */
    public long prepare(Course course, Job job, JobInfo jobInfo, String account) {
        try {
            if (!claim(account, job.getJobId())) {
                return -1;
            }
            QuizPaper paper = courseUtil.fetchQuizPaper(course, job, jobInfo);
            if (paper.isAlreadySubmitted()) {
                // 这一章早就交过了，超星给的是批阅页。不挂到「待确认」，
                // 不然每次重跑课程都会把已完成的小测再列一遍。
                return -1;
            }
            if (paper.size() == 0) {
                return saveRaw(account, course, job, paper, STATUS_BROKEN,
                        "没解析出题目（可能不是题目页，见 app/quiz-dump/）");
            }
            aiAnswerService.fill(paper.getQuestions(), paper.getCourseName());
            int manual = 0;
            for (QuizQuestion q : paper.getQuestions()) {
                if (q.isManual()) {
                    manual++;
                }
            }

            // 策略是 auto_* 且整份卷子每道题都过了校验并达到置信度门槛 → 直接交。
            // 只要有一道题没把握，就整份挂回「待确认」等人看：提交不可逆，
            // 交错的代价比漏交大得多。
            String mode = AiAnswerService.Config.load().answerMode;
            if (manual == 0 && QuizMode.auto(mode)
                    && confidentEnough(paper, QuizMode.minConfidenceRank(mode))) {
                Map<String, String> answers = new LinkedHashMap<>();
                for (QuizQuestion q : paper.getQuestions()) {
                    answers.put(q.getId(), q.getAnswer());
                }
                boolean ok;
                try {
                    ok = courseUtil.submitQuiz(paper, answers);
                } catch (Exception e) {
                    ok = false;
                    log.warn("自动提交异常 {} / {}: {}", course.getTitle(), job.getTitle(), e.toString());
                }
                if (ok) {
                    log.info("自动提交成功: {} / {}（{} 题，策略 {}）",
                            course.getTitle(), job.getTitle(), paper.size(), QuizMode.label(mode));
                    return saveRaw(account, course, job, paper, STATUS_SUBMITTED,
                            "AI 自动提交成功（" + QuizMode.label(mode) + "）");
                }
                // 交失败了别丢：退回待确认，让人手工点一次，顺便能看到服务端到底说了什么
                return saveRaw(account, course, job, paper, STATUS_PENDING,
                        "AI 自动提交被服务端拒绝，请人工确认后重试");
            }

            String note = manual > 0 ? (manual + " 题 AI 没把握，需人工") : null;
            return saveRaw(account, course, job, paper, STATUS_PENDING, note);
        } catch (Exception e) {
            log.warn("准备测验失败 {} / {}: {}", course.getTitle(), job.getTitle(), e.toString());
            try {
                return saveRaw(account, course, job, null, STATUS_BROKEN,
                        "抓题失败: " + e.getMessage());
            } catch (Exception ignore) {
                return -1;
            }
        }
    }

    /**
     * 同一个任务点只留一条记录，返回 true 表示「可以开始抓题」。
     *
     * <p>刷课重启太常见了，不去重的话「待确认」里会堆出一串一模一样的卷子。
     * 待确认 / 已提交 / 用户跳过的直接跳过；上次抓题失败的删掉重来 ——
     * 不然超星改了页面之后这一章就永远恢复不了了。
     */
    private boolean claim(String account, String jobId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, status FROM th_wk_quiz WHERE account = ? AND job_id = ? ORDER BY id DESC",
                account, jobId);
        for (Map<String, Object> row : rows) {
            int st = ((Number) row.get("status")).intValue();
            long id = ((Number) row.get("id")).longValue();
            if (st == STATUS_FAILED) {
                // 提交失败那条留着给用户看，但允许重新抓一份
                continue;
            }
            if (st == STATUS_BROKEN) {
                jdbc.update("DELETE FROM th_wk_quiz WHERE id = ?", id);
                continue;
            }
            log.info("测验 {} 已有记录 #{}（status={}），不再重复抓题", jobId, id, st);
            return false;
        }
        return true;
    }

    /**
     * 这份卷子够不够格自动交：每道题都有答案，且置信度都不低于门槛。
     *
     * <p>置信度是模型自己报的，空值/认不出的值一律当「不够格」——
     * 宁可让人看一眼，也不拿一个不知道是什么的东西去交卷。
     */
    private boolean confidentEnough(QuizPaper paper, int minRank) {
        for (QuizQuestion q : paper.getQuestions()) {
            String a = q.getAnswer();
            if (a == null || a.trim().isEmpty()) {
                return false;
            }
            if (confidenceRank(q.getConfidence()) < minRank) {
                return false;
            }
        }
        return true;
    }

    private static int confidenceRank(String c) {
        if ("high".equalsIgnoreCase(c)) {
            return 3;
        }
        if ("medium".equalsIgnoreCase(c)) {
            return 2;
        }
        if ("low".equalsIgnoreCase(c)) {
            return 1;
        }
        return 0;
    }

    private long saveRaw(String account, Course course, Job job, QuizPaper paper,
                         int status, String note) {
        String json = null;
        if (paper != null) {
            try {
                json = MAPPER.writeValueAsString(paper);
            } catch (Exception e) {
                json = null;
            }
        }
        final String payload = json;
        final String acc = account;
        final String cid = course.getCourseId();
        final String cname = course.getTitle();
        final String jid = job.getJobId();
        final String title = job.getTitle();

        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO th_wk_quiz (account, course_id, course_name, job_id, title,"
                            + " paper_json, status, error_message, create_time, update_time)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, acc);
            ps.setString(2, cid);
            ps.setString(3, cname);
            ps.setString(4, jid);
            ps.setString(5, title);
            ps.setString(6, payload);
            ps.setInt(7, status);
            ps.setString(8, note);
            LocalDateTime now = LocalDateTime.now();
            ps.setObject(9, now);
            ps.setObject(10, now);
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? -1 : key.longValue();
    }

    /** 待确认列表。题目和 AI 建议都在 paper_json 里，整份返回给界面。 */
    public List<Map<String, Object>> list(String account, int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, course_name, title, status, error_message, create_time, paper_json"
                        + " FROM th_wk_quiz WHERE account = ? AND status IN (?,?)"
                        + " ORDER BY id DESC LIMIT ?",
                account, STATUS_PENDING, STATUS_BROKEN, limit);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>(row);
            Object json = row.get("paper_json");
            m.remove("paper_json");
            if (json != null) {
                try {
                    QuizPaper p = MAPPER.readValue(json.toString(), QuizPaper.class);
                    m.put("questions", p.getQuestions());
                    m.put("questionCount", p.size());
                } catch (Exception e) {
                    m.put("questions", new ArrayList<>());
                    m.put("questionCount", 0);
                }
            }
            out.add(m);
        }
        return out;
    }

    /** 待确认条数，给总览页显示小红点用。 */
    public int pendingCount(String account) {
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM th_wk_quiz WHERE account = ? AND status IN (?,?)",
                    Integer.class, account, STATUS_PENDING, STATUS_BROKEN);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 提交一份测验。
     *
     * @param answers 题目 id → 用户确认过的答案；null 表示沿用 paper 里的 answer
     * @return {@code {ok:true/false, message:"..."}}
     */
    public Map<String, Object> submit(long id, Map<String, String> answers) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT paper_json, status FROM th_wk_quiz WHERE id = ?", id);
        if (rows.isEmpty()) {
            result.put("ok", false);
            result.put("message", "记录不存在");
            return result;
        }
        int status = ((Number) rows.get(0).get("status")).intValue();
        if (status == STATUS_SUBMITTED) {
            result.put("ok", false);
            result.put("message", "这份已经提交过了");
            return result;
        }

        QuizPaper paper;
        try {
            paper = MAPPER.readValue(String.valueOf(rows.get(0).get("paper_json")), QuizPaper.class);
        } catch (Exception e) {
            result.put("ok", false);
            result.put("message", "记录内容损坏: " + e.getMessage());
            return result;
        }

        if (paper.isAlreadySubmitted()) {
            updateStatus(id, STATUS_SUBMITTED, "抓到的已经是批阅页，说明早就交过了");
            result.put("ok", false);
            result.put("message", "这份之前就交过了（抓到的是批阅页）");
            return result;
        }

        // 用户改过的答案覆盖 AI 建议
        if (answers != null && !answers.isEmpty()) {
            for (QuizQuestion q : paper.getQuestions()) {
                String a = answers.get(q.getId());
                if (a != null && !a.trim().isEmpty()) {
                    q.setAnswer(a.trim());
                }
            }
        }

        try {
            boolean ok = courseUtil.submitQuiz(paper, answers);
            // 交成功就顺手把「本次成绩」读回来，界面上直接能看到分
            if (ok) {
                courseUtil.fillScore(paper);
            }
            updatePaper(id, paper, ok ? STATUS_SUBMITTED : STATUS_FAILED,
                    ok ? null : "服务端拒绝提交");
            result.put("ok", ok);
            result.put("score", paper.getScore());
            result.put("fullScore", paper.getFullScore());
            result.put("message", ok ? "已提交" : "服务端拒绝提交，详情看 app/boot.log");
        } catch (Exception e) {
            updatePaper(id, paper, STATUS_FAILED, "提交异常: " + e.getMessage());
            result.put("ok", false);
            result.put("message", "提交异常: " + e.getMessage());
        }
        return result;
    }

    /**
     * 让 AI 重新作答一份待确认的卷子。
     *
     * <p>典型场景：抓题那一刻网络断了 / 模型抽风，整份没答案，只留一句
     * 「N 题 AI 没把握」。这时候不该让用户手填，重跑一遍就行。
     *
     * @return {@code {ok, answered, total, message}}
     */
    public Map<String, Object> retry(long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT paper_json, status, course_name FROM th_wk_quiz WHERE id = ?", id);
        if (rows.isEmpty()) {
            result.put("ok", false);
            result.put("message", "记录不存在");
            return result;
        }
        int status = ((Number) rows.get(0).get("status")).intValue();
        if (status == STATUS_SUBMITTED) {
            result.put("ok", false);
            result.put("message", "这份已经交过了，重试会改不了分");
            return result;
        }
        QuizPaper paper;
        try {
            paper = MAPPER.readValue(String.valueOf(rows.get(0).get("paper_json")), QuizPaper.class);
        } catch (Exception e) {
            result.put("ok", false);
            result.put("message", "记录内容损坏: " + e.getMessage());
            return result;
        }
        if (paper.isAlreadySubmitted()) {
            result.put("ok", false);
            result.put("message", "抓到的是批阅页，说明早就交过了");
            return result;
        }
        // 清掉上一轮的答案再问，免得旧答案被当成 AI 结果留下来
        for (QuizQuestion q : paper.getQuestions()) {
            q.setSuggestion(null);
            q.setConfidence(null);
            q.setReason(null);
            q.setManual(false);
            q.setNote(null);
        }
        String courseName = rows.get(0).get("course_name") == null
                ? null : String.valueOf(rows.get(0).get("course_name"));
        try {
            aiAnswerService.fill(paper.getQuestions(), courseName);
        } catch (Exception e) {
            log.warn("重试作答失败 #{}: {}", id, e.toString());
        }
        int answered = 0;
        for (QuizQuestion q : paper.getQuestions()) {
            String a = q.getSuggestion() == null ? q.getAnswer() : q.getSuggestion();
            if (a != null && !a.trim().isEmpty()) {
                answered++;
            }
        }
        String msg = answered == paper.size()
                ? "AI 已重新作答（" + answered + " 题）"
                : answered + " 题有答案，" + (paper.size() - answered) + " 题仍需人工";
        updatePaper(id, paper, STATUS_PENDING, msg);
        result.put("ok", true);
        result.put("answered", answered);
        result.put("total", paper.size());
        result.put("message", msg);
        return result;
    }

    /** 重新查一次成绩（已经交过的那些）。 */
    public Map<String, Object> refreshScore(long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT paper_json FROM th_wk_quiz WHERE id = ?", id);
        if (rows.isEmpty()) {
            result.put("ok", false);
            result.put("message", "记录不存在");
            return result;
        }
        QuizPaper paper;
        try {
            paper = MAPPER.readValue(String.valueOf(rows.get(0).get("paper_json")), QuizPaper.class);
        } catch (Exception e) {
            result.put("ok", false);
            result.put("message", "记录内容损坏");
            return result;
        }
        boolean ok = courseUtil.fillScore(paper);
        if (ok) {
            try {
                // 只写 paper_json，**不碰 update_time** —— 那一列是「交卷时间」，
                // 刷新成绩时改它，界面上所有卷子都会变成刚刚才交的。
                jdbc.update("UPDATE th_wk_quiz SET paper_json = ? WHERE id = ?",
                        MAPPER.writeValueAsString(paper), id);
            } catch (Exception e) {
                log.warn("成绩写回失败 #{}: {}", id, e.toString());
            }
        }
        result.put("ok", ok);
        result.put("score", paper.getScore());
        result.put("fullScore", paper.getFullScore());
        result.put("scoreAt", paper.getScoreAt());
        result.put("message", ok ? "已更新成绩" : "还没出分，或者批阅页读不到");
        return result;
    }

    /** 最近交过的几份（带成绩），按**提交时间**倒序 —— 不是按 id。 */
    public List<Map<String, Object>> recent(String account, int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, course_name, title, status, error_message, update_time, paper_json"
                        + " FROM th_wk_quiz WHERE account = ? AND status = ?"
                        + " ORDER BY update_time DESC, id DESC LIMIT ?",
                account, STATUS_SUBMITTED, limit);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", row.get("id"));
            m.put("course_name", row.get("course_name"));
            m.put("title", row.get("title"));
            m.put("update_time", row.get("update_time"));
            Object json = row.get("paper_json");
            if (json != null) {
                try {
                    QuizPaper p = MAPPER.readValue(json.toString(), QuizPaper.class);
                    m.put("score", p.getScore());
                    m.put("fullScore", p.getFullScore());
                    m.put("scoreAt", p.getScoreAt());
                    m.put("questionCount", p.size());
                } catch (Exception ignored) {
                    // 坏记录就当没分
                }
            }
            out.add(m);
        }
        return out;
    }

    /** 改状态 + 把 paper 的改动（答案、成绩）一起写回去。 */
    private void updatePaper(long id, QuizPaper paper, int status, String message) {
        try {
            jdbc.update("UPDATE th_wk_quiz SET status = ?, error_message = ?, paper_json = ?,"
                            + " update_time = ? WHERE id = ?",
                    status, message, MAPPER.writeValueAsString(paper), LocalDateTime.now(), id);
        } catch (Exception e) {
            log.warn("写回测验记录失败 #{}: {}", id, e.toString());
            updateStatus(id, status, message);
        }
    }

    public void skip(long id, String reason) {
        updateStatus(id, STATUS_SKIPPED, reason == null ? "用户跳过" : reason);
    }

    private void updateStatus(long id, int status, String message) {
        jdbc.update("UPDATE th_wk_quiz SET status = ?, error_message = ?, update_time = ? WHERE id = ?",
                status, message, LocalDateTime.now(), id);
    }
}
