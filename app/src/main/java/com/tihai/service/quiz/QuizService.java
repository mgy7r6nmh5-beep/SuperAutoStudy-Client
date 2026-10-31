package com.tihai.service.quiz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tihai.common.Job;
import com.tihai.common.JobInfo;
import com.tihai.common.QuizPaper;
import com.tihai.common.QuizQuestion;
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
            updateStatus(id, ok ? STATUS_SUBMITTED : STATUS_FAILED,
                    ok ? null : "服务端拒绝提交");
            result.put("ok", ok);
            result.put("message", ok ? "已提交" : "服务端拒绝提交，详情看 app/boot.log");
        } catch (Exception e) {
            updateStatus(id, STATUS_FAILED, "提交异常: " + e.getMessage());
            result.put("ok", false);
            result.put("message", "提交异常: " + e.getMessage());
        }
        return result;
    }

    public void skip(long id, String reason) {
        updateStatus(id, STATUS_SKIPPED, reason == null ? "用户跳过" : reason);
    }

    private void updateStatus(long id, int status, String message) {
        jdbc.update("UPDATE th_wk_quiz SET status = ?, error_message = ?, update_time = ? WHERE id = ?",
                status, message, LocalDateTime.now(), id);
    }
}
