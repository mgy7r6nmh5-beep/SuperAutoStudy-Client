package com.tihai.service.superstar.impl;

import com.alibaba.nacos.api.exception.NacosException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tihai.common.*;
import com.tihai.constant.GlobalConstant;
import com.tihai.constant.JobTypeConstant;
import com.tihai.constant.StudyPolicy;
import com.tihai.domain.chaoxing.SuperStarLog;
import com.tihai.domain.chaoxing.SuperStarTask;
import com.tihai.domain.chaoxing.WkUser;
import com.tihai.dubbo.dto.CourseSubmitTaskDTO;
import com.tihai.dubbo.pojo.course.Course;
import com.tihai.enums.BizCodeEnum;
import com.tihai.enums.WkTaskStatusEnum;
import com.tihai.exception.BusinessException;
import com.tihai.factory.CustomThreadFactory;
import com.tihai.factory.PriorityRejectPolicy;
import com.tihai.manager.RollBackManager;
import com.tihai.mapper.SuperStarMapper;

import com.tihai.properties.ThreadPoolProperties;
import com.tihai.queue.PriorityTaskWrapper;
import com.tihai.service.superstar.SuperStarLogService;
import com.tihai.service.superstar.SuperStarLoginService;
import com.tihai.service.superstar.SuperStarTaskService;
import com.tihai.service.superstar.SuperStarUserService;
import com.tihai.utils.CourseUtil;
import com.tihai.utils.ServerInfoUtil;
import lombok.extern.slf4j.Slf4j;
import ma.glasnost.orika.MapperFacade;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;


/**
 * @Copyright : DuanInnovator
 * @Description : 超星学习通-任务启动和实现
 * @Author : DuanInnovator
 * @CreateTime : 2025/4/26
 * @Link : <a href="https://github.com/DuanInnovator/TiHaiWuYou-Admin/tree/mine-admin">...</a>
 **/
@Service
@Slf4j
public class SuperStarTaskServiceImpl extends ServiceImpl<SuperStarMapper, SuperStarTask> implements SuperStarTaskService {

    @Autowired
    private MapperFacade mapperFacade;

    @Autowired
    private ThreadPoolExecutor taskExecutor;

    @Autowired
    private SuperStarUserService userService;

    @Autowired
    private CourseUtil courseUtil;

    @Autowired
    private com.tihai.service.quiz.QuizService quizService;

    @Autowired
    private SuperStarLogService superStarLogService;

    @Autowired
    private SuperStarLoginService loginService;

    @Autowired
    private RollBackManager rb;

    @Autowired
    private ServerInfoUtil serverInfoUtil;

    @Autowired
    private ThreadPoolProperties config;



    private volatile boolean isShuttingDown = false;
    private final Object shutdownLock = new Object();


    // 日志批量保存的缓冲队列
    private final BlockingQueue<SuperStarLog> logQueue = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<Long, SuperStarLog> logMap = new ConcurrentHashMap<>();

    // 正在刷的任务 id（进程内去重，防止同一门课被两个线程同时刷）
    private static final Set<String> RUNNING_TASKS = ConcurrentHashMap.newKeySet();

    public SuperStarTaskServiceImpl() throws NacosException {
    }

    public void shutdownThreadPoolGracefully() throws NacosException {
        // 提前检查减少同步块竞争
        if (isShuttingDown || taskExecutor.isShutdown()) {
            return;
        }

        synchronized (shutdownLock) {
            // 再次检查（双重检查锁模式）
            if (isShuttingDown || taskExecutor.isShutdown()) {
                return;
            }
            isShuttingDown = true;

            try {
                // 1. 停止接受新任务
                taskExecutor.shutdown();
                log.info("线程池开始关闭，等待现有任务完成...");

                // 2. 分阶段等待
                // 第一阶段：等待正常完成
                if (!taskExecutor.awaitTermination(1, TimeUnit.MINUTES)) {
                    log.warn("等待超时，尝试强制停止剩余任务...");

                    // 第二阶段：强制停止
                    List<Runnable> unfinishedTasks = taskExecutor.shutdownNow();

                    // 3. 处理未完成任务
                    if (!unfinishedTasks.isEmpty()) {
                        log.warn("有{}个任务被强制停止，开始回滚状态...", unfinishedTasks.size());
                        processUnfinishedTasks(unfinishedTasks);
                    }

                    // 最后检查
                    if (!taskExecutor.awaitTermination(1, TimeUnit.MINUTES)) {
                        log.error("线程池最终未能完全关闭");
                    }
                }
            } catch (InterruptedException e) {
                // 处理中断
                List<Runnable> unfinishedTasks = taskExecutor.shutdownNow();
                if (!unfinishedTasks.isEmpty()) {
                    processUnfinishedTasks(unfinishedTasks);
                }
                Thread.currentThread().interrupt();
                throw new NacosException(NacosException.SERVER_ERROR, "线程池关闭被中断");
            } finally {
                log.info("线程池关闭完成，当前状态: isShutdown={}, isTerminated={}",
                        taskExecutor.isShutdown(), taskExecutor.isTerminated());

            }
        }
    }

    private void processUnfinishedTasks(List<Runnable> unfinishedTasks) {
        unfinishedTasks.forEach(task -> {
            if (task instanceof PriorityTaskWrapper) {
                String taskId = ((PriorityTaskWrapper) task).getTaskId();
                try {
                    SuperStarTask dbTask = this.getById(taskId);
                    if (dbTask != null &&
                            (WkTaskStatusEnum.PROCESSING.getCode().equals(dbTask.getStatus()) ||
                                    WkTaskStatusEnum.EXAM.getCode().equals(dbTask.getStatus()))) {
                        markPaused(dbTask);
                    }
                } catch (Exception e) {
                    log.error("回滚任务状态失败, taskId={}", taskId, e);
                }
            }
        });
    }

    public void restartThreadPoolAndTasks() {
        synchronized (shutdownLock) {
            if (!taskExecutor.isShutdown()) {
                return;
            }

            BlockingQueue<Runnable> queue = new PriorityBlockingQueue<>(
                    config.getQueueCapacity(), PriorityTaskWrapper.PRIORITY_ORDER);

            // 重新构建线程池：学习线程数永远固定为 1
            taskExecutor = new ThreadPoolExecutor(
                    StudyPolicy.LEARNING_THREADS,
                    StudyPolicy.LEARNING_THREADS,
                    config.getKeepAlive(),
                    TimeUnit.SECONDS,
                    queue,
                    new CustomThreadFactory(config.getThreadNamePrefix()),
                    new PriorityRejectPolicy()
            );
            taskExecutor.allowCoreThreadTimeOut(config.isAllowCoreThreadTimeout());
            this.startChaoxingTask();
        }
    }

    @PostConstruct
    public void init() {
        // 启动一个单独的线程来处理日志的批量保存
        new Thread(this::batchSaveLogs).start();
    }

    private void batchSaveLogs() {
        List<SuperStarLog> batch = new ArrayList<>(100);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                SuperStarLog log = logQueue.poll(1, TimeUnit.SECONDS);
                if (log != null) {
                    batch.add(log);
                    logMap.put(log.getId(), log); // 合并更新
                }
                // 批量保存条件：达到100条或超时
                if (batch.size() >= 100 || (log == null && !batch.isEmpty())) {
                    List<SuperStarLog> toSave = new ArrayList<>(logMap.values());

                    if (log != null) {
                        log.setMachineNum(serverInfoUtil.getCurrentServerInstance());
                    }
                    superStarLogService.saveOrUpdateBatch(toSave);
                    logMap.clear();
                    batch.clear();

                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (NacosException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private SuperStarLog createNewLog(SuperStarTask task) {
        SuperStarLog log = mapperFacade.map(task, SuperStarLog.class);
        log.setStartTime(LocalDateTime.now());
        log.setStatus(WkTaskStatusEnum.PROCESSING.getCode());
        // 首次插入，获取自增ID
        superStarLogService.save(log);
        return log;
    }

    /**
     * 根据账号和课程id获取超星学习任务
     *
     * @param loginAccount 账号
     * @param courseId     课程id
     */
    @Override
    public SuperStarTask getSuperStarTask(String loginAccount, String courseId) {
        LambdaQueryWrapper<SuperStarTask> superStarTaskLambdaQueryWrapper = new LambdaQueryWrapper<>();
        superStarTaskLambdaQueryWrapper.eq(SuperStarTask::getLoginAccount, loginAccount);
        superStarTaskLambdaQueryWrapper.eq(SuperStarTask::getCourseId, courseId);
        return this.getOne(superStarTaskLambdaQueryWrapper);
    }

    /**
     * 根据账号和课程id获取超星学习任务
     *
     * @param loginAccount 账号
     * @param courseName   课程名
     */
    @Override
    public SuperStarTask getSuperStarTaskByCourseName(String loginAccount, String courseName) {
        LambdaQueryWrapper<SuperStarTask> superStarTaskLambdaQueryWrapper = new LambdaQueryWrapper<>();
        superStarTaskLambdaQueryWrapper.eq(SuperStarTask::getLoginAccount, loginAccount);
        superStarTaskLambdaQueryWrapper.eq(SuperStarTask::getCourseName, courseName);
        return this.getOne(superStarTaskLambdaQueryWrapper);
    }

    /**
     * 添加任务到等待队列
     *
     * @param courseSubmitTaskDTO 任务信息
     * @throws NacosException nacos异常
     */
    public void addChaoXingTask(CourseSubmitTaskDTO courseSubmitTaskDTO) throws NacosException {
        if (StringUtils.isBlank(courseSubmitTaskDTO.getLoginAccount())) {
            throw new IllegalArgumentException("登录账号不能为空");
        }
        if (StringUtils.isBlank(courseSubmitTaskDTO.getCourseName())) {
            throw new IllegalArgumentException("课程名称不能为空");
        }
        Integer priority = courseSubmitTaskDTO.getPriority();
        if (priority == null) {
            priority = StudyPolicy.MIN_PRIORITY;
        }
        if (priority < StudyPolicy.MIN_PRIORITY || priority > StudyPolicy.MAX_PRIORITY) {
            throw new IllegalArgumentException("优先级要在 " + StudyPolicy.MIN_PRIORITY
                    + "~" + StudyPolicy.MAX_PRIORITY + " 之间");
        }

        SuperStarTask task = courseSubmitTaskDTO.getCourseId() == null
                ? getSuperStarTaskByCourseName(courseSubmitTaskDTO.getLoginAccount(), courseSubmitTaskDTO.getCourseName())
                : getSuperStarTask(courseSubmitTaskDTO.getLoginAccount(), courseSubmitTaskDTO.getCourseId());
        if (task == null) {
            SuperStarTask chaoXingTask = mapperFacade.map(courseSubmitTaskDTO, SuperStarTask.class);
            chaoXingTask.setStatus(WkTaskStatusEnum.PENDING.getCode());
            chaoXingTask.setId(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")));
            chaoXingTask.setPriority(priority);
            chaoXingTask.setRetryCount(0);
            chaoXingTask.setCreatTime(LocalDateTime.now());
            chaoXingTask.setMachineNum(serverInfoUtil.getCurrentServerInstance());
            this.baseMapper.insert(chaoXingTask);
        } else if (WkTaskStatusEnum.FINISHED.getCode().equals(task.getStatus())) {
            // 已完成课程重新加入：重置为待处理并重新复核，不新建重复行（P0-11）
            LambdaUpdateWrapper<SuperStarTask> reset = new LambdaUpdateWrapper<>();
            reset.eq(SuperStarTask::getId, task.getId())
                    .set(SuperStarTask::getStatus, WkTaskStatusEnum.PENDING.getCode())
                    .set(SuperStarTask::getRetryCount, 0)
                    .set(SuperStarTask::getCreatTime, LocalDateTime.now())
                    .set(SuperStarTask::getPriority, priority)
                    .set(SuperStarTask::getMachineNum, serverInfoUtil.getCurrentServerInstance());
            this.update(null, reset);
        } else {
            throw new BusinessException(BizCodeEnum.TASK_ALREADY_EXIST.getCode(), BizCodeEnum.TASK_ALREADY_EXIST.getMsg());
        }
        startChaoxingTask();
    }

    /**
     * 超星学习任务启动
     */
    @Override
    public synchronized void startChaoxingTask() {
        if (isShuttingDown || taskExecutor.isShutdown()) {
            return;
        }
        // 单课串行：已有课程在排队或执行时不再认领新的。
        // 执行完由 finally 里的 scheduleNext() 推进下一门。
        List<SuperStarTask> active = list(new LambdaQueryWrapper<SuperStarTask>()
                .in(SuperStarTask::getStatus,
                        WkTaskStatusEnum.QUEUE.getCode(),
                        WkTaskStatusEnum.PROCESSING.getCode())
                .last("LIMIT 1"));
        if (active != null && !active.isEmpty()) {
            return;
        }
        // 优先级 1 = 最高；同优先级按加入时间先来先刷。
        LambdaQueryWrapper<SuperStarTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SuperStarTask::getStatus, WkTaskStatusEnum.PENDING.getCode());
        wrapper.apply("COALESCE(retry_count, 0) < {0}", StudyPolicy.MAX_AUTO_RETRY);
        wrapper.orderByAsc(SuperStarTask::getPriority);
        wrapper.orderByAsc(SuperStarTask::getCreatTime);
        wrapper.last("LIMIT 1");
        List<SuperStarTask> tasks = list(wrapper);
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        SuperStarTask task = tasks.get(0);

        // 原子认领：只有真正把 PENDING 翻转成 QUEUE 的调用才继续，
        // 并发进来多次扫描也不会把同一门课重复投进线程池。
        if (!claim(task.getId())) {
            return;
        }
        task.setStatus(WkTaskStatusEnum.QUEUE.getCode());
        try {
            task.setMachineNum(serverInfoUtil.getCurrentServerInstance());
        } catch (NacosException e) {
            rollbackToPending(task.getId());
            throw new RuntimeException(e);
        }

        String runKey = task.getId() != null ? task.getId() : task.getCourseName();
        Runnable taskWrapper = new PriorityTaskWrapper(() -> {
            // 同一门课只许一个线程在刷。命中重复时必须把已认领的 QUEUE
            // 回滚成 PENDING，否则这门课会永远停在「队列中」。
            if (!RUNNING_TASKS.add(runKey)) {
                log.warn("跳过重复任务：{}（已有线程在刷这门课）", task.getCourseName());
                rollbackToPending(task.getId());
                return;
            }
            try {
                markProcessing(task.getId());
                executeCourseTask(task);
            } catch (Exception e) {
                handleTaskFailure(task, e);
            } finally {
                RUNNING_TASKS.remove(runKey);
                // 正常跑完（已完成 / 异常）才推进下一门；需要原地重试的任务
                // 留在待处理，等 5 分钟自愈，避免紧密失败循环。
                if (shouldAdvance(task.getId())) {
                    scheduleNext();
                }
            }
        }, task.getPriority(), task.getId());

        taskExecutor.execute(taskWrapper);
    }

    private boolean shouldAdvance(String taskId) {
        SuperStarTask latest = this.getById(taskId);
        return latest == null
                || !WkTaskStatusEnum.PENDING.getCode().equals(latest.getStatus());
    }

    /**
     * 把 PENDING 原子认领为 QUEUE。
     */
    private boolean claim(String taskId) {
        LambdaUpdateWrapper<SuperStarTask> claim = new LambdaUpdateWrapper<>();
        claim.eq(SuperStarTask::getId, taskId)
                .eq(SuperStarTask::getStatus, WkTaskStatusEnum.PENDING.getCode())
                .set(SuperStarTask::getStatus, WkTaskStatusEnum.QUEUE.getCode());
        return this.update(null, claim);
    }

    private void rollbackToPending(String taskId) {
        LambdaUpdateWrapper<SuperStarTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(SuperStarTask::getId, taskId)
                .eq(SuperStarTask::getStatus, WkTaskStatusEnum.QUEUE.getCode())
                .set(SuperStarTask::getStatus, WkTaskStatusEnum.PENDING.getCode());
        this.update(null, wrapper);
    }

    private void markProcessing(String taskId) {
        LambdaUpdateWrapper<SuperStarTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(SuperStarTask::getId, taskId)
                .set(SuperStarTask::getStatus, WkTaskStatusEnum.PROCESSING.getCode());
        this.update(null, wrapper);
    }

    private void markAbnormal(String taskId) {
        LambdaUpdateWrapper<SuperStarTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(SuperStarTask::getId, taskId)
                .set(SuperStarTask::getStatus, WkTaskStatusEnum.ABNORMAL.getCode());
        this.update(null, wrapper);
    }

    private void markPaused(SuperStarTask task) {
        task.setStatus(WkTaskStatusEnum.PAUSED.getCode());
        this.updateById(task);
        SuperStarLog log = superStarLogService.getLatestLogByLoginAccount(
                task.getLoginAccount(), task.getCourseName());
        if (log != null) {
            log.setRemark("服务停止，课程暂停");
            logQueue.offer(log);
        }
    }

    /**
     * 执行线程抛出未捕获异常时的兜底：按 retry_count 决定回待处理还是标异常。
     */
    private void handleTaskFailure(SuperStarTask task, Exception e) {
        int retry = task.getRetryCount() == null ? 0 : task.getRetryCount();
        LambdaUpdateWrapper<SuperStarTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(SuperStarTask::getId, task.getId());
        if (retry >= StudyPolicy.MAX_AUTO_RETRY) {
            wrapper.set(SuperStarTask::getStatus, WkTaskStatusEnum.ABNORMAL.getCode());
            log.error("课程执行异常且已达自动重试上限: {}", task.getCourseName(), e);
            SuperStarLog log = superStarLogService.getLatestLogByLoginAccount(
                    task.getLoginAccount(), task.getCourseName());
            if (log != null) {
                log.setErrorMessage("系统异常，已达自动重试上限（" + StudyPolicy.MAX_AUTO_RETRY
                        + " 轮），可点「续刷 / 重试」恢复");
                logQueue.offer(log);
            }
        } else {
            wrapper.set(SuperStarTask::getStatus, WkTaskStatusEnum.PENDING.getCode())
                    .set(SuperStarTask::getRetryCount, retry + 1);
            log.warn("课程执行异常，稍后重试: {}（第 {}/{} 轮）",
                    task.getCourseName(), retry + 1, StudyPolicy.MAX_AUTO_RETRY, e);
        }
        this.update(null, wrapper);
    }

    /**
     * 执行完一门课再推进下一门。放到线程池里跑，避免在任务线程里递归调用
     * 导致调用栈随课程数增长。
     */
    private void scheduleNext() {
        if (isShuttingDown || taskExecutor.isShutdown()) {
            return;
        }
        try {
            taskExecutor.execute(this::startChaoxingTask);
        } catch (RejectedExecutionException e) {
            log.warn("推进下一门课时线程池已关闭，本次不再排队");
        }
    }

    /**
     * 执行超星学习任务
     *
     * @param task 超星学习任务
     */
    public void executeCourseTask(SuperStarTask task) {
        WkUser user = userService.getUserByAccount(task.getLoginAccount());
        SuperStarLog log = superStarLogService.getLatestLogByLoginAccount(task.getLoginAccount(), task.getCourseName());

        if (log == null) {
            log = createNewLog(task);
        } else {
            log.setStartTime(LocalDateTime.now());
            // FIX(2026-10-11)「控制台永远不显示进行中」：
            // 重试是复用上一次那条日志行（getLatestLogByLoginAccount 取的是最新一条），
            // 但这里原来只改了 start_time —— 状态还是上一轮的终态（4 已完成 / 5 异常），
            // error_message 也还是上一轮的结论（「未刷完：…」「超星返回 9010…」）。
            // 控制台按日志状态显示，于是整轮都在显示上一轮的结论，永远看不到「进行中」。
            // 所以复用时要把它打回干净的「进行中」状态。
            log.setStatus(WkTaskStatusEnum.PROCESSING.getCode());
            log.setErrorMessage(null);
            log.setRemark(null);
            log.setEndTime(null);
            logQueue.offer(log);   // 交给批量写线程，约 1 秒内落库
        }


        if (user == null || user.getCookies() == null) {
            int retry = task.getRetryCount() == null ? 0 : task.getRetryCount();
            if (retry < StudyPolicy.MAX_AUTO_RETRY) {
                LambdaUpdateWrapper<SuperStarTask> wrapper = new LambdaUpdateWrapper<>();
                wrapper.eq(SuperStarTask::getId, task.getId())
                        .set(SuperStarTask::getStatus, WkTaskStatusEnum.PENDING.getCode())
                        .set(SuperStarTask::getRetryCount, retry + 1);
                this.update(null, wrapper);
            } else {
                markAbnormal(task.getId());
            }
            WkUser wkUser = new WkUser();
            wkUser.setAccount(task.getLoginAccount());
            wkUser.setPassword(task.getPassword());
            loginService.login(wkUser, true);
            log.setStatus(WkTaskStatusEnum.ABNORMAL.getCode());
            log.setErrorMessage("用户未登录");
            logQueue.offer(log);
            return;
        }

        try {
            // 风控退避期内不要发课程页 / 章节页 / cards 页请求 —— 超星的风控窗口
            // 是按「最后一次请求」滚动的，退避期里漏请求出去只会把窗口续期。
            CourseUtil.awaitRiskClear("扫描课程: " + task.getCourseName());

            Course readyCourse = new Course();
            courseUtil.setAccount(task.getLoginAccount());
            courseUtil.setCookies(user.getCookies());
            if (task.getCourseId() != null) {
                readyCourse = courseUtil.getCourseList().stream()
                        .filter(course -> course.getCourseId().equals(task.getCourseId()))
                        .findFirst().orElse(null);
            } else if (task.getCourseName() != null) {
                List<Course> courseList = courseUtil.getCourseList();
                 readyCourse = Optional.ofNullable(courseList)
                        .orElse(Collections.emptyList())
                        .stream()
                        .filter(course -> course != null && task != null)
                        .filter(course -> {
                            // 标准化处理
                            String dbTitle = StringUtils.normalizeSpace(course.getTitle());
                            String taskName = StringUtils.normalizeSpace(task.getCourseName());

                            return dbTitle.equalsIgnoreCase(taskName);
                        })
                        .findFirst()
                        .orElse(null);
            }

            if (readyCourse == null) {
                loginService.login(user, true);
                log.setStatus(WkTaskStatusEnum.ABNORMAL.getCode());
                log.setErrorMessage(GlobalConstant.COURSE_INFO_GET_FAIL);
                logQueue.offer(log);
                // 直接 return 会漏掉队列状态回写，任务永远停在「队列中(2)」
                markAbnormal(task.getId());
                return;
            }

            List<CoursePoint> pointList = courseUtil.getCoursePoint(
                    readyCourse.getCourseId(), readyCourse.getClazzId(), readyCourse.getCpi());

            processCoursePoints(task, log, readyCourse, pointList);
        } catch (Exception e) {
            log.setStatus(WkTaskStatusEnum.ABNORMAL.getCode());
            log.setErrorMessage("系统异常");
            logQueue.offer(log);
            // 同上，异常退出也要回写，否则任务卡在「队列中(2)」永远不动
            markAbnormal(task.getId());
        }
    }

    private void processCoursePoints(SuperStarTask task, SuperStarLog log, Course readyCourse, List<CoursePoint> pointList) {
        if (log == null) {
            log = mapperFacade.map(task, SuperStarLog.class);
            log.setId(Long.valueOf(UUID.randomUUID().toString()));
            log.setStatus(WkTaskStatusEnum.PROCESSING.getCode());
            logQueue.offer(log);
        }

        List<ChapterPoint> chapterPointList = pointList.get(0).getPoints();
        int totalChapters = chapterPointList.size();

        // 断点位置。优先按上一轮记下的 chapter_id 在新章节表里定位 —— 超星偶尔会
        // 调整章节顺序，纯按数组下标续刷会跳章或把已刷完的章再刷一遍。
        // 找不到（章节被删/改名）或者老数据没有 chapter_id 时，退回下标。
        int pointIndex = log.getCurrentChapterIndex() != null ? log.getCurrentChapterIndex() : 0;
        String resumeChapterId = log.getChapterId();
        if (resumeChapterId != null && !resumeChapterId.trim().isEmpty()) {
            for (int i = 0; i < chapterPointList.size(); i++) {
                if (resumeChapterId.equals(chapterPointList.get(i).getId())) {
                    pointIndex = i;
                    break;
                }
            }
        }
        if (pointIndex < 0 || pointIndex >= chapterPointList.size()) {
            pointIndex = 0;
        }
        if (pointIndex > 0) {
            int failedCount = parseIdSet(log.getFailedChapters()).size();
            boolean hasVideoResume = log.getVideoPositionSeconds() != null
                    && log.getVideoPositionSeconds() > 0;
            if (pointIndex > 0 || failedCount > 0 || hasVideoResume) {
                String videoResume = hasVideoResume
                        ? "，视频续播点 " + log.getVideoPositionSeconds() + "s" : "";
                System.out.println("续刷课程: " + readyCourse.getTitle()
                        + " 从第 " + (pointIndex + 1) + "/" + totalChapters + " 章接着刷"
                        + "，失败章节 " + failedCount + " 个" + videoResume);
            }
        }
        // 本轮没通过服务端校验的章节 id（视频 isPassed 为 false、任务点抛异常等）
        Set<String> failedChapterIds = new LinkedHashSet<>();
        // 因为「超星说这章 pending==0」而被跳过的章节。如果最后 getRealProgress
        // 又说还有 pending，说明那份快照不准，下一轮必须强制重刷它们，
        // 否则会陷入「跳过 → 仍有 pending → 再跳过」的死循环。
        Set<String> skippedChapters = new LinkedHashSet<>();

        // 上一轮失败的章节 → 本轮只重刷它们，其余章节直接跳过，不做无效扫描
        Set<String> retryOnly = parseIdSet(log.getFailedChapters());

        // 一次性拿到「每章还剩几个任务点」（章节id → 剩余数）。
        // 拉不到就是 null，退化成原来「每章都拉 cards 页」的行为，不影响正确性。
        Map<String, Integer> chapterPending = courseUtil.getChapterPending(
                readyCourse.getCourseId(), readyCourse.getClazzId(), readyCourse.getCpi());

        while (pointIndex < chapterPointList.size()) {

            try {
                ChapterPoint chapterPoint = chapterPointList.get(pointIndex);
                String chapterId = chapterPoint.getId();
                log.setCurrentChapterIndex(pointIndex);
                log.setChapterId(chapterId);

                // ① 上一轮有失败章节：本轮只重刷这些章。
                //    这一条必须排在 ② 前面 —— 被判失败的章要无条件重新处理，
                //    不能被「pending==0」的快照挡住。
                if (!retryOnly.isEmpty() && !retryOnly.contains(chapterId)) {
                    pointIndex++;
                    log.setCurrentChapterIndex(pointIndex);
                    continue;
                }

                // ② 超星页面上这章已经没有任何待完成任务点 → 跳过，省一次 cards 请求
                if (chapterPending != null && Integer.valueOf(0).equals(chapterPending.get(chapterId))) {
                    skippedChapters.add(chapterId);
                    pointIndex++;
                    log.setCurrentChapterIndex(pointIndex);
                    continue;
                }

                Pair<List<Job>, List<JobInfo>> result = courseUtil.getJobList(
                        readyCourse.getClazzId().toString(),
                        readyCourse.getCourseId(),
                        readyCourse.getCpi(),
                        chapterPoint.getId());

                List<Job> jobs = result.getFirst();
                List<JobInfo> jobInfo = result.getSecond();

                // 先判断「章节未开放」：getJobList 对未开放章节返回空 jobs + notOpen 标记，
                // 必须排在 jobs.isEmpty() 之前，否则永远走「无任务点」分支、不回滚也不报错。
                if (jobInfo != null && !jobInfo.isEmpty()
                        && Boolean.TRUE.equals(jobInfo.get(0).getNotOpen())) {
                    pointIndex = Math.max(0, pointIndex - 1);
                    try {
                        rb.addTimes(chapterPoint.getId());
                    } catch (Exception e) {
                        log.setStatus(WkTaskStatusEnum.ABNORMAL.getCode());
                        log.setErrorMessage("章节未开放（已达回滚上限）");
                        log.setEndTime(LocalDateTime.now());
                        logQueue.offer(log);
                        markAbnormal(task.getId());
                        return;
                    }
                    continue;
                }

                if (jobs == null || jobs.isEmpty()) {
                    pointIndex++;
                    continue;
                }

                boolean chapterOk = true;
                for (Job job : jobs) {
                    try {

                        // 做题 / 章节测验（type=workid）走半自动：抓题 → 问 AI →
                        // 落库等人工确认，然后**继续下一章**。
                        //
                        // 这里绝不能停下来等用户点确认 —— 刷课线程是单线程串行的，
                        // 一停整门课就卡死。所以 prepare() 只负责把卷子备好，
                        // 真正的提交由界面点「确认」时走 QuizController。
                        if (JobTypeConstant.QUESTION.equals(job.getType())) {
                            prepareQuiz(readyCourse, job, jobInfo.get(0), log);
                            continue;
                        }

                        switch (job.getType()) {

                            case JobTypeConstant.VIDEO:
                                boolean videoPassed = false;
                                boolean threw = false;
                                try {
                                    videoPassed = courseUtil.studyVideo(readyCourse, job, jobInfo.get(0), "Video", log);
                                } catch (Exception e) {
                                    threw = true;
                                }
                                // 只有「抛异常」才换音频通道重试（保持原行为，避免失败视频耗时翻倍）
                                if (!videoPassed && threw) {
                                    try {
                                        videoPassed = courseUtil.studyVideo(readyCourse, job, jobInfo.get(0), "Audio", log);
                                    } catch (Exception e) {
                                        videoPassed = false;
                                    }
                                }
                                // studyVideo 返回服务端裁决（isPassed），false 表示
                                // 「上报被接受但超星没计分」，这种章节不能算刷完。
                                if (!videoPassed) {
                                    chapterOk = false;
                                }
                                break;
                            case JobTypeConstant.DOCUMENT:
                                // FIX: 老实现不看返回值，文档上报失败也算这一章通过，
                                // 结果「超星还有待完成任务点」和「0 个章节未通过校验」
                                // 同时成立，用户查不出卡在哪。
                                if (!courseUtil.studyDocument(readyCourse, job)) {
                                    chapterOk = false;
                                }
                                break;
                            case JobTypeConstant.HYPERLINK:
                                // FIX(2026-10-11): 超链接任务点（type=hyperlink）原来没有分支，
                                // 落到 default 被当成「已完成」，超星却一直挂着它 —— 课永远刷不完。
                                // 实测端点 /ananas/job/hyperlink 返回 {"msg":"添加考核点成功","status":true}。
                                if (!courseUtil.studyHyperlink(readyCourse, job)) {
                                    chapterOk = false;
                                }
                                break;
                            case JobTypeConstant.READ:
                                courseUtil.studyRead(readyCourse, job, jobInfo.get(0), log);
                                break;
                            case JobTypeConstant.QUESTION:
                                // 理论上上面那个 if 已经拦掉了，这里是兜底：
                                // 万一将来有人删了那个 if，也不会变成静默跳过。
                                prepareQuiz(readyCourse, job, jobInfo.get(0), log);
                                break;
                            default:
                                // FIX(2026-10-11): 老实现静默 break，未知类型（讨论 / 直播 / 测验…）
                                // 被当成「已完成」，于是超星那边一直挂着待完成任务点却查不出原因。
                                // 至少把类型名打出来，方便定位。
                                System.out.println(Thread.currentThread().getName()
                                        + "跳过不支持的任务点类型[" + job.getType() + "]: "
                                        + readyCourse.getTitle() + " / " + job.getTitle());
                                break;

                        }

                    } catch (Exception e) {
                        chapterOk = false;
                        log.setEndTime(LocalDateTime.now());
                        log.setErrorMessage("任务异常，任务ID=" + job.getTitle());
                    }
                }
                if (!chapterOk) {
                    failedChapterIds.add(chapterId);
                }
                pointIndex++;

                // FIX: 原来写的是 pointIndex - 1。pointIndex 此刻已经 ++，再减 1 就回到
                // 「刚做完的那一章」，续刷时会把上一章重做一遍。存「下一个待处理章节」
                // 才是续刷该有的语义。
                log.setCurrentChapterIndex(pointIndex);
                log.setEndTime(LocalDateTime.now());
                log.setCurrentProgress(BigDecimal.valueOf(
                        pointIndex * 100.0 / Math.max(1, totalChapters)));
                logQueue.offer(log);


            } catch (Exception e) {
                log.setStatus(WkTaskStatusEnum.ABNORMAL.getCode());
                log.setErrorMessage("章节处理异常");
                logQueue.offer(log);
                // break 出来时任务会一直停在「队列中(2)」不动
                // （既不进 FINISHED 块，也没抛给上层 Runnable 的重试逻辑）。
                // 显式标成异常，队列状态才跟事实一致。
                markAbnormal(task.getId());
                break;
            }
        }

        // ---------------------------------------------------------------
        // 完成判定：以「超星自己写在课程页上的数字」为准，不再凭本地循环走完就宣布完成。
        //
        // 老实现是 pointIndex >= chapterPointList.size() 就写 FINISHED + 100%。
        // 但 pointIndex 只说明「本地把章节列表遍历了一遍」，跟服务端是否计分毫无关系。
        // 实测踩到的后果：线性代数 30 章、超星页面上明明写着「已完成 4、待完成任务点 27」，
        // 工具却把队列状态写成「已完成」、进度写成 100% —— 这就是用户看到的
        // 「课没刷完，却显示已完成」。
        //
        // 现在拉课程页拿 pending（待完成任务点合计）：
        //   pending == 0 且本次无失败章节  → 才算真完成
        //   否则                          → 标异常，并把超星的真实数字写进错误信息
        // ---------------------------------------------------------------
        boolean loopFinished = pointIndex >= totalChapters;
        Map<String, Integer> real = courseUtil.getRealProgress(
                readyCourse.getCourseId(), readyCourse.getClazzId(), readyCourse.getCpi());

        log.setEndTime(LocalDateTime.now());
        log.setCurrentChapterIndex(pointIndex);
        log.setFailedChapters(failedChapterIds.isEmpty() ? null : String.join(",", failedChapterIds));

        if (real != null) {
            int total = real.getOrDefault("total", 0);
            int done = real.getOrDefault("done", 0);
            int pending = real.getOrDefault("pending", 0);
            int noTask = real.getOrDefault("noTask", 0);
            // 真实完成度 = 已完成章节 / 有任务点的章节（本来就没任务点的章节不该拉低百分比）
            int denominator = Math.max(1, total - noTask);
            double realPercent = Math.min(100.0, done * 100.0 / denominator);

            // 靠「pending==0」跳过的章，如果超星这里又说还有待完成任务点，
            // 说明那份章节快照过期了 → 记成失败，下一轮强制重刷
            if (pending > 0 && !skippedChapters.isEmpty()) {
                failedChapterIds.addAll(skippedChapters);
                log.setFailedChapters(String.join(",", failedChapterIds));
            }

            if (pending == 0 && failedChapterIds.isEmpty()) {
                log.setCurrentProgress(BigDecimal.valueOf(100));
                log.setErrorMessage(null);
                log.setStatus(WkTaskStatusEnum.FINISHED.getCode());
                log.setRemark(String.format("任务完成: %s（超星已确认无待完成任务点，%d/%d 章）",
                        readyCourse.getTitle(), done, total));
                task.setStatus(WkTaskStatusEnum.FINISHED.getCode());
            } else {
                log.setCurrentProgress(BigDecimal.valueOf(realPercent));
                log.setStatus(WkTaskStatusEnum.ABNORMAL.getCode());
                log.setRemark(String.format("未完成: %s（超星：已完成 %d/%d 章）",
                        readyCourse.getTitle(), done, total));
                log.setErrorMessage(String.format(
                        "未刷完：超星仍有 %d 个待完成任务点（已完成 %d/%d 章）；本次 %d 个章节未通过服务端校验",
                        pending, done, total, failedChapterIds.size()));
                task.setStatus(WkTaskStatusEnum.ABNORMAL.getCode());
                // FIX(2026-10-11)「未刷完却永远重试不了」：
                // current_chapter_index 是「上次遍历到第几章」的续跑指针，会被持久化。
                // 一旦它顶到末尾（本轮把章节扫完了，但课没刷完），下一轮就会
                // 0 秒扫完、直接判「未刷完」并标异常 —— 而且**永远重复这个 1 秒循环**，
                // 课再也刷不动。实测：
                //   人工智能通识I  current_chapter_index=291（= 总章数）→ 每轮 1 秒失败
                //   高等数学先修课 current_chapter_index=16 （= 总章数）→ 同上
                // 所以「扫到末尾但没刷完」时把指针归零，保证下一轮真的从头重扫。
                // 注意只在顶到末尾时归零：中途被打断（pointIndex < totalChapters）要保留，
                // 那是正常的断点续跑。
                if (pointIndex >= totalChapters) {
                    log.setCurrentChapterIndex(0);
                    log.setChapterId(null);
                }
            }
            logQueue.offer(log);
            persistTaskStatus(task);
            return;
        }

        // 拉不到课程页（网络/登录问题）→ 退回本地判断，
        // 且只敢在「确实遍历完 + 没有失败章节」时才宣布完成
        if (loopFinished && failedChapterIds.isEmpty()) {
            log.setCurrentProgress(BigDecimal.valueOf(100));
            log.setStatus(WkTaskStatusEnum.FINISHED.getCode());
            log.setErrorMessage(null);
            log.setRemark("任务完成: " + readyCourse.getTitle() + "（本地校验通过，未能回查超星）");
            task.setStatus(WkTaskStatusEnum.FINISHED.getCode());
        } else {
            log.setStatus(WkTaskStatusEnum.ABNORMAL.getCode());
            log.setRemark("未完成: " + readyCourse.getTitle());
            log.setErrorMessage(String.format(
                    "未刷完：本地遍历到第 %d/%d 章，其中 %d 个章节未通过服务端校验（且未能回查超星确认）",
                    pointIndex, totalChapters, failedChapterIds.size()));
            task.setStatus(WkTaskStatusEnum.ABNORMAL.getCode());
            // 同上：顶到末尾但没刷完 → 指针归零，否则下一轮 0 秒扫完、死循环
            if (pointIndex >= totalChapters) {
                log.setCurrentChapterIndex(0);
                log.setChapterId(null);
            }
        }
        logQueue.offer(log);
        persistTaskStatus(task);
    }

    /**
     * 只更新队列状态，不整行覆盖 —— 避免把并发修改的重试次数、优先级等字段写回旧值。
     */
    private void persistTaskStatus(SuperStarTask task) {
        LambdaUpdateWrapper<SuperStarTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(SuperStarTask::getId, task.getId())
                .set(SuperStarTask::getStatus, task.getStatus());
        this.update(null, wrapper);
    }

    /**
     * 把「逗号分隔的章节 id」解析成集合。
     *
     * <p>数据库里 failed_chapters 用逗号分隔而不是 JSON，是因为章节 id 全是纯数字，
     * 逗号不可能出现在里面，解析简单，出问题时直接 SELECT 出来就能读。
     */
    private static Set<String> parseIdSet(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.trim().isEmpty()) {
            return out;
        }
        for (String part : raw.split(",")) {
            String id = part.trim();
            if (!id.isEmpty()) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * 备一份章节测验：抓题 → 问 AI → 落库，然后立刻返回。
     *
     * <p>**这里不能等用户确认。** 刷课线程核心线程数是 1（单课串行是硬约束），
     * 一旦在这里阻塞等人点按钮，整门课就停了。所以只把卷子准备好，
     * 提交交给界面上点「确认」时走 {@code QuizController}。
     *
     * <p>任何异常都吞掉：做题失败不能让整门课的刷课跟着崩。
     */
    private void prepareQuiz(Course course, Job job, JobInfo jobInfo, SuperStarLog log) {
        String who = Thread.currentThread().getName();
        try {
            System.out.println(who + "[quiz] 准备测验: "
                    + course.getTitle() + " / " + job.getTitle());
            long id = quizService.prepare(course, job, jobInfo, log.getLoginAccount());
            System.out.println(who + "[quiz] 已生成待确认卷 #" + id + ": " + job.getTitle());
        } catch (Throwable t) {
            System.out.println(who + "[quiz] 准备测验失败（已跳过）: " + t);
        }
    }
}