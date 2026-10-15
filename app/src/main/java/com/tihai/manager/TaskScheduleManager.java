package com.tihai.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tihai.domain.chaoxing.SuperStarLog;
import com.tihai.domain.chaoxing.SuperStarTask;
import com.tihai.domain.chaoxing.WkUser;
import com.tihai.dubbo.pojo.course.Course;
import com.tihai.enums.WkTaskStatusEnum;
import com.tihai.mapper.SuperStarMapper;
import com.tihai.service.superstar.SuperStarLogService;
import com.tihai.service.superstar.SuperStarUserService;
import com.tihai.service.superstar.impl.SuperStarTaskServiceImpl;
import com.tihai.utils.CourseUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @Copyright : DuanInnovator
 * @Description : 线程任务管理 + 定时自愈
 * @Author : DuanInnovator
 * @CreateTime : 2025/3/27
 * @Link : <a href="https://github.com/DuanInnovator/TiHaiWuYou-Admin/tree/mine-admin">...</a>
 **/
@Service
public class TaskScheduleManager {

    @Autowired
    private SuperStarTaskServiceImpl taskService;

    @Autowired
    private SuperStarMapper superStarMapper;

    @Autowired
    private CourseUtil courseUtil;

    @Autowired
    private SuperStarUserService userService;

    @Autowired
    private SuperStarLogService logService;

    private volatile boolean isNightMode = false;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "self-heal-scheduler");
                t.setDaemon(true);
                return t;
            });

    @PostConstruct
    public void init() {
        // 定时自愈：每 5 分钟把「异常(5)」的课程救回「待处理(0)」并自动入队。
        //
        // 这是 2026-10-11 修「高数未刷完」的关键一环：
        // processCoursePoints 跑完一轮后，只要有 1 个待完成任务点就标 ABNORMAL(5)。
        // 但 startChaoxingTask() 只捞 PENDING(0)，重新提交同一门课还会被
        // TASK_ALREADY_EXIST 拒掉，于是 ABNORMAL 的课程**只能靠重启后端**才能再跑，
        // 用户就看到「显示未刷完却永远不刷」。
        //
        // 周期性地把 ABNORMAL→PENDING，就能让撞过一次风控 / 漏过一个点的课程
        // 自动重试，直到超星侧 pending 真正归零。
        //
        // 只动 ABNORMAL（终态 = 上一轮已跑完），不动正在跑的 QUEUE(2)，
        // 避免把运行中的课程重复拉起导致双跑。
        long delayMin = 5;
        scheduler.scheduleWithFixedDelay(this::selfHeal, delayMin, delayMin, TimeUnit.MINUTES);
    }

    private void selfHeal() {
        try {
            // 先复核「是不是其实已经刷完了」：线程池 core-size=1 时，排在长课程后面的
            // 课程永远拿不到线程，也就永远没人重新判定它的完成度 —— 界面一直挂着上一轮的
            // 「未刷完：超星仍有 N 个待完成任务点」。这里不等线程，直接问超星课程页。
            verifyRealCompletion();

            LambdaUpdateWrapper<SuperStarTask> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(SuperStarTask::getStatus, WkTaskStatusEnum.ABNORMAL.getCode())
                    .set(SuperStarTask::getStatus, WkTaskStatusEnum.PENDING.getCode())
                    .set(SuperStarTask::getRetryCount, 0);
            int n = superStarMapper.update(null, wrapper);
            if (n > 0) {
                taskService.startChaoxingTask();
            }
        } catch (Exception ignore) {
            // 自愈失败不影响主流程；下一轮还会再试
        }
    }

    /**
     * 完成度复核（2026-10-15 新增）。
     *
     * <p><b>解决的 bug</b>：课程<b>实际已经刷完</b>（超星课程页 pending == 0），
     * 但界面上还挂着「未刷完」。
     *
     * <p>成因：完成判定只在「该课程自己跑完一轮」时执行（见
     * {@code SuperStarTaskServiceImpl#processCoursePoints}）。而线程池 core-size 默认 1，
     * 一门课在跑时，队列里排在后面的课程永远轮不到 → 它的完成度没人复核，
     * 队列状态和日志就永远停在上一轮的结论上。实测：国家安全教育在作业补齐、
     * 超星 pending 已归零后，仍在「队列中」挂了 1 小时以上，用户看到「未刷完」。
     *
     * <p><b>做法</b>：每轮自愈对「队列中(2) / 异常(5)」的课程直接拉超星课程页
     * （{@link CourseUtil#getRealProgress}，只读，不动任何刷课状态）：
     * pending == 0 且解析到章节 → 直接判 FINISHED 并把日志行改写成「任务完成」。
     * 解析失败（拿到登录页等）返回 null，直接跳过，绝不误判。
     *
     * <p>会话隔离：一律走 {@link CourseUtil#forkSession()}，不碰单例上的 cookie，
     * 否则会和正在跑的刷课线程互相覆盖账号。
     */
    @SuppressWarnings("unchecked")
    private void verifyRealCompletion() {
        List<SuperStarTask> rows = superStarMapper.selectList(
                new LambdaQueryWrapper<SuperStarTask>()
                        .in(SuperStarTask::getStatus,
                                WkTaskStatusEnum.QUEUE.getCode(),
                                WkTaskStatusEnum.ABNORMAL.getCode()));
        if (rows == null || rows.isEmpty()) {
            return;
        }
        // 同一账号的多门课共用一份会话与课程列表，避免重复登录/重复拉列表
        Map<String, Object[]> sessions = new HashMap<>();
        for (SuperStarTask task : rows) {
            try {
                String account = task.getLoginAccount();
                String courseId = task.getCourseId();
                if (account == null || courseId == null) {
                    continue;
                }
                Object[] session = sessions.get(account);
                if (session == null) {
                    WkUser user = userService.getUserByAccount(account);
                    if (user == null || user.getCookies() == null || user.getCookies().isEmpty()) {
                        sessions.put(account, new Object[]{null, null});
                        continue;
                    }
                    CourseUtil cu = courseUtil.forkSession();
                    cu.setCookies(user.getCookies());
                    cu.setAccount(account);
                    session = new Object[]{cu, cu.getCourseList()};
                    sessions.put(account, session);
                }
                CourseUtil cu = (CourseUtil) session[0];
                if (cu == null) {
                    continue;
                }
                List<Course> courses = (List<Course>) session[1];
                if (courses == null) {
                    continue;
                }
                Course target = null;
                for (Course c : courses) {
                    if (courseId.equals(c.getCourseId())) {
                        target = c;
                        break;
                    }
                }
                if (target == null) {
                    continue;
                }

                Map<String, Integer> real = cu.getRealProgress(
                        courseId, target.getClazzId(), target.getCpi());
                if (real == null) {
                    continue;   // 拉不到课程页（网络/登录问题）→ 不误判
                }
                int total = real.getOrDefault("total", 0);
                int done = real.getOrDefault("done", 0);
                int pending = real.getOrDefault("pending", 0);
                if (total <= 0 || pending > 0) {
                    continue;   // 确实还没刷完 → 保持原样，等它自己跑
                }

                task.setStatus(WkTaskStatusEnum.FINISHED.getCode());
                superStarMapper.updateById(task);

                // 日志行与队列行共用同一个 id（见 th_wk_log / th_wk_queue）；
                // id 不是数字时宁可不写日志，也不能让 saveOrUpdate 当成新行插入。
                Long logId = null;
                try {
                    logId = Long.parseLong(task.getId());
                } catch (NumberFormatException ignore) {
                    // 非数字 id：只改队列状态
                }
                if (logId != null) {
                    SuperStarLog log = new SuperStarLog();
                    log.setId(logId);
                    log.setLoginAccount(account);
                    log.setCourseName(task.getCourseName());
                    log.setStatus(WkTaskStatusEnum.FINISHED.getCode());
                    log.setCurrentProgress(BigDecimal.valueOf(100));
                    log.setCurrentChapterIndex(0);
                    log.setEndTime(LocalDateTime.now());
                    log.setErrorMessage(null);
                    log.setRemark(String.format("任务完成: %s（超星已确认无待完成任务点，%d/%d 章）",
                            task.getCourseName(), done, total));
                    logService.saveOrUpdate(log);
                }

                System.out.println("完成度复核: " + task.getCourseName()
                        + " 超星已无待完成任务点（" + done + "/" + total + " 章），标记为已完成");
            } catch (Exception ignore) {
                // 单门课复核失败不影响其它课程
            }
        }
    }

//    @PostConstruct
//    public void init() throws NacosException {
//        checkTimeAndAdjust();
//    }

    // 每分钟检查一次时间
//    @Scheduled(cron = "0 * * * * ?")
//    public void checkTimeAndAdjust() throws NacosException {
//        LocalTime now = LocalTime.now();
//        LocalTime nightStart = LocalTime.of(10,32 ); // 23:00
//        LocalTime morningStart = LocalTime.of(5, 0);  // 05:00
//
//        if (now.isAfter(nightStart) || now.isBefore(morningStart)) {
//
//                isNightMode = true;
//                taskService.shutdownThreadPoolGracefully();
//
//        } else {
//
//                // 进入日间模式
//                isNightMode = false;
//                taskService.restartThreadPoolAndTasks();
//
//        }
//    }
}
