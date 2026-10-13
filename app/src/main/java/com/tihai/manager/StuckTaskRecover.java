package com.tihai.manager;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tihai.domain.chaoxing.SuperStarTask;
import com.tihai.enums.WkTaskStatusEnum;
import com.tihai.mapper.SuperStarMapper;
import com.tihai.service.superstar.SuperStarTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * @Copyright : DuanInnovator
 * @Description : 启动自愈 —— 把上一轮进程遗留的「僵尸任务」救回队列
 * @CreateTime : 2026/10/11
 *
 * <p>解决的实际问题（2026-10-11 用户反馈「显示未刷完却不刷」）：
 *
 * <p>任务被线程池认领时，状态会被置成「队列中(2)」。后端如果被强杀
 * （点「停止服务」/ 任务管理器结束进程 / 崩溃），这些任务就永远停在 2；
 * 刷挂了的那门课停在「异常(5)」。而 {@code startChaoxingTask()} 只捞
 * 「待处理(0)」——队列里一个 0 都没有，于是点「启动服务」什么都不发生，
 * 控制台上却显示一堆「未刷完」。
 *
 * <p><b>为什么放在 ApplicationReadyEvent 而不是 @PostConstruct：</b>
 * 此刻进程刚起来，线程池里一个任务都没有，所以库里所有
 * 「进行中(1) / 队列中(2) / 考试中(3) / 暂停中(6)」<b>必然是上一个进程的孤儿</b>，
 * 无条件重置是安全的，不需要去猜时间戳。
 *
 * <p><b>为什么先做「已完成」同步：</b>
 * 后端刷完一门课只写 {@code th_wk_log}，从不回写 {@code th_wk_queue.status}，
 * 所以已完成的课在队列里也停在 2。不先按日志同步成 4，就会被当成孤儿
 * 重新刷一遍——既浪费时间又会白白触发超星风控。
 *
 * <p><b>为什么最后要自动入队：</b>
 * 让「启动服务」这一个动作就能把课跑起来，不再依赖用户记得去点
 * 「续刷 / 重试」。
 **/
@Component
@Slf4j
public class StuckTaskRecover implements ApplicationListener<ApplicationReadyEvent> {

    @Autowired
    private SuperStarMapper superStarMapper;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SuperStarTaskService superStarTaskService;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            recover();
        } catch (Exception e) {
            log.error("启动自愈失败（不影响服务启动；可在控制台点「续刷 / 重试」手动恢复）", e);
        }
    }

    private void recover() {
        int done = syncFinishedFromLog();
        int reset = resetOrphans();
        int retry = retryAbnormal();

        if (done + reset + retry > 0) {
            log.warn("启动自愈：同步已完成 {} 个 / 重置中断 {} 个 / 重试失败 {} 个", done, reset, retry);
        } else {
            log.info("启动自愈：没有需要恢复的任务");
        }

        // 重置完立刻入队。失败也不影响服务本身，控制台的「扫描队列」按钮可以再来一次。
        try {
            superStarTaskService.startChaoxingTask();
        } catch (Exception e) {
            log.error("启动自愈后自动入队失败，可在控制台点「扫描队列」重试", e);
        }
    }

    /**
     * ① 已经刷完、但队列状态没回写的课 → 同步成「已完成(4)」，避免被当孤儿重刷。
     * 判据与 gui/console.py 的 reset_stuck_tasks() 完全一致：取每门课最新一条日志的状态。
     */
    private int syncFinishedFromLog() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return jdbc.update(
                "UPDATE th_wk_queue q "
                        + "  JOIN (SELECT course_name, MAX(id) AS mid FROM th_wk_log GROUP BY course_name) m "
                        + "    ON m.course_name = q.course_name "
                        + "  JOIN th_wk_log l ON l.id = m.mid "
                        + "   SET q.status = ? "
                        + " WHERE q.status IN (?, ?, ?, ?) AND l.status = ?",
                WkTaskStatusEnum.FINISHED.getCode(),
                WkTaskStatusEnum.PROCESSING.getCode(),
                WkTaskStatusEnum.QUEUE.getCode(),
                WkTaskStatusEnum.EXAM.getCode(),
                WkTaskStatusEnum.PAUSED.getCode(),
                WkTaskStatusEnum.FINISHED.getCode());
    }

    /**
     * ② 剩下的「进行中 / 队列中 / 考试中 / 暂停中」全部是上一轮遗留的孤儿 → 回到「待处理(0)」。
     */
    private int resetOrphans() {
        LambdaUpdateWrapper<SuperStarTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.in(SuperStarTask::getStatus,
                        WkTaskStatusEnum.PROCESSING.getCode(),
                        WkTaskStatusEnum.QUEUE.getCode(),
                        WkTaskStatusEnum.EXAM.getCode(),
                        WkTaskStatusEnum.PAUSED.getCode())
                .set(SuperStarTask::getStatus, WkTaskStatusEnum.PENDING.getCode());
        return superStarMapper.update(null, wrapper);
    }

    /**
     * ③「异常(5)」是终态，而且没有别的重试入口（重新提交同一门课会被
     * TASK_ALREADY_EXIST 拒掉），所以一并捞回来重试，否则失败的课永远没救。
     * 同时把 retry_count 归零，给它们一轮完整的新额度。
     */
    private int retryAbnormal() {
        LambdaUpdateWrapper<SuperStarTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(SuperStarTask::getStatus, WkTaskStatusEnum.ABNORMAL.getCode())
                .set(SuperStarTask::getStatus, WkTaskStatusEnum.PENDING.getCode())
                .set(SuperStarTask::getRetryCount, 0);
        return superStarMapper.update(null, wrapper);
    }
}
