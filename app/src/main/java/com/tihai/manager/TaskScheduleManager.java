package com.tihai.manager;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tihai.domain.chaoxing.SuperStarTask;
import com.tihai.enums.WkTaskStatusEnum;
import com.tihai.mapper.SuperStarMapper;
import com.tihai.service.superstar.impl.SuperStarTaskServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
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
