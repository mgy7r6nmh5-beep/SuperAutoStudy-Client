package com.tihai.service.superstar.impl;

import com.alibaba.nacos.api.exception.NacosException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tihai.common.*;
import com.tihai.constant.GlobalConstant;
import com.tihai.constant.JobTypeConstant;
import com.tihai.constant.RetryConstant;
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
import com.tihai.properties.StudyProperties;
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
    private SuperStarLogService superStarLogService;

    @Autowired
    private SuperStarLoginService loginService;

    @Autowired
    private RollBackManager rb;

    @Autowired
    private ServerInfoUtil serverInfoUtil;

    @Autowired
    private ThreadPoolProperties config;

    @Autowired
    private StudyProperties studyProperties;


    private volatile boolean isShuttingDown = false;
    private final Object shutdownLock = new Object();


    // 日志批量保存的缓冲队列
    private final BlockingQueue<SuperStarLog> logQueue = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<Long, SuperStarLog> logMap = new ConcurrentHashMap<>();

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
                        dbTask.setStatus(WkTaskStatusEnum.PAUSED.getCode());
                        this.updateById(dbTask);
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

            BlockingQueue<Runnable> queue = new PriorityBlockingQueue<>(config.getQueueCapacity());

            // 重新构建线程池
            taskExecutor = new ThreadPoolExecutor(
                    config.getCoreSize(),
                    config.getMaxSize(),
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
        SuperStarTask task = null;
        if (courseSubmitTaskDTO.getCourseId() == null) {
            task = getSuperStarTaskByCourseName(courseSubmitTaskDTO.getLoginAccount(), courseSubmitTaskDTO.getCourseName());
        } else {
            task = getSuperStarTask(courseSubmitTaskDTO.getLoginAccount(), courseSubmitTaskDTO.getCourseId());
        }
        if (task == null) {
            SuperStarTask chaoXingTask = mapperFacade.map(courseSubmitTaskDTO, SuperStarTask.class);
            chaoXingTask.setStatus(WkTaskStatusEnum.PENDING.getCode());
            chaoXingTask.setId(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))); //当前version使用UUID
            chaoXingTask.setPriority(1);
            chaoXingTask.setCreatTime(LocalDateTime.now());
            chaoXingTask.setMachineNum(serverInfoUtil.getCurrentServerInstance());
            this.baseMapper.insert(chaoXingTask);
        } else {
            throw new BusinessException(BizCodeEnum.TASK_ALREADY_EXIST.getCode(), BizCodeEnum.TASK_ALREADY_EXIST.getMsg());
        }
        //TODO 这里似乎不太合理,等待后续优化.......
        startChaoxingTask();
    }

    /**
     * 超星学习任务启动
     */
    @Override
    public synchronized void startChaoxingTask() {
        LambdaQueryWrapper<SuperStarTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SuperStarTask::getStatus, WkTaskStatusEnum.PENDING.getCode());
        wrapper.le(SuperStarTask::getRetryCount, RetryConstant.DEFAULT_RETRY_COUNT);
        wrapper.orderByDesc(SuperStarTask::getPriority);
        List<SuperStarTask> tasks = list(wrapper);
//        executeCourseTask(tasks.get(0));

        for (SuperStarTask task : tasks) {
            // FIX: 原子认领。原实现是"先 SELECT 出 PENDING，再逐条 updateById 改成 QUEUE"，
            // 读与写之间没有原子性。而控制台是并发提交多门课、每个 POST 末尾都会调到这里，
            // 于是多个请求读到同一批 PENDING 任务，把同一门课重复投进线程池
            // （实测「线性代数」被 3 个线程同时刷同一个视频，5 个线程全被占满，
            //   后面的「体育（1）」一直排不上队）。
            // 改成带 WHERE status=PENDING 的条件更新：只有真正把 PENDING 翻转成 QUEUE
            // 的那一次调用才算认领成功，其余直接跳过。
            LambdaUpdateWrapper<SuperStarTask> claim = new LambdaUpdateWrapper<>();
            claim.eq(SuperStarTask::getId, task.getId())
                    .eq(SuperStarTask::getStatus, WkTaskStatusEnum.PENDING.getCode());

            task.setStatus(WkTaskStatusEnum.QUEUE.getCode());
            try {
                task.setMachineNum(serverInfoUtil.getCurrentServerInstance());
            } catch (NacosException e) {
                throw new RuntimeException(e);
            }
            boolean isUpdated = this.update(task, claim);
            if (!isUpdated) {
                continue;   // 已被别的调用认领，跳过，避免重复执行
            }
            Runnable taskWrapper = new PriorityTaskWrapper(() -> {
                try {
                    executeCourseTask(task);
                } catch (Exception e) {
                    if (task.getRetryCount() == RetryConstant.DEFAULT_RETRY_COUNT) {
                        task.setStatus(WkTaskStatusEnum.ABNORMAL.getCode());
                    } else {
                        task.setRetryCount(task.getRetryCount() + 1);
                        task.setStatus(WkTaskStatusEnum.PENDING.getCode());
                    }
                    this.updateById(task);
                }
            }, task.getPriority(), task.getId());

            taskExecutor.execute(taskWrapper);
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
            if (task.getRetryCount() < RetryConstant.DEFAULT_RETRY_COUNT) {
                task.setStatus(WkTaskStatusEnum.PENDING.getCode());
                task.setRetryCount(task.getRetryCount() + 1);
            } else {
                task.setStatus(WkTaskStatusEnum.ABNORMAL.getCode());
            }
            WkUser wkUser = new WkUser();
            wkUser.setAccount(task.getLoginAccount());
            wkUser.setPassword(task.getPassword());
            loginService.login(wkUser, true);
            this.updateById(task);
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
                // FIX: 直接 return 会漏掉队列状态回写，任务永远停在「队列中(2)」
                task.setStatus(WkTaskStatusEnum.ABNORMAL.getCode());
                this.updateById(task);
                return;
            }

            List<CoursePoint> pointList = courseUtil.getCoursePoint(
                    readyCourse.getCourseId(), readyCourse.getClazzId(), readyCourse.getCpi());

            processCoursePoints(task, log, readyCourse, pointList);
        } catch (Exception e) {
            log.setStatus(WkTaskStatusEnum.ABNORMAL.getCode());
            log.setErrorMessage("系统异常");
            logQueue.offer(log);
            // FIX: 同上，异常退出也要回写，否则任务卡在「队列中(2)」永远不动
            task.setStatus(WkTaskStatusEnum.ABNORMAL.getCode());
            this.updateById(task);
        }
    }

    private void processCoursePoints(SuperStarTask task, SuperStarLog log, Course readyCourse, List<CoursePoint> pointList) {
        if (log == null) {
            log = mapperFacade.map(task, SuperStarLog.class);
            log.setId(Long.valueOf(UUID.randomUUID().toString()));
            log.setStatus(WkTaskStatusEnum.PROCESSING.getCode());
            logQueue.offer(log);
        }

        int pointIndex = log.getCurrentChapterIndex() != null ? log.getCurrentChapterIndex() : 0;

        List<ChapterPoint> chapterPointList = pointList.get(0).getPoints();
        int totalChapters = chapterPointList.size();
        // 记录有多少个章节没能通过服务端校验（视频 isPassed 为 false、任务点抛异常等）
        int failedChapters = 0;

        while (pointIndex < chapterPointList.size()) {

            try {
                log.setCurrentChapterIndex(pointIndex);
                ChapterPoint chapterPoint = chapterPointList.get(pointIndex);
                Pair<List<Job>, List<JobInfo>> result = courseUtil.getJobList(
                        readyCourse.getClazzId().toString(),
                        readyCourse.getCourseId(),
                        readyCourse.getCpi(),
                        chapterPoint.getId());

                List<Job> jobs = result.getFirst();
                List<JobInfo> jobInfo = result.getSecond();

                if (jobs.isEmpty()) {
                    pointIndex++;
                    continue;
                }

                if (jobs.stream().findFirst().map(job -> job instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) job).get("notOpen"))).orElse(false)) {
                    pointIndex--;
                    rb.addTimes(chapterPoint.getId());
                    continue;
                }

                boolean chapterOk = true;
                // 「只刷视频」模式：命中 study.chaoxing.video-only-course-ids 的课程，
                // 只处理 video，其余任务点（document / read / workid=做题 / hyperlink）一律跳过。
                boolean videoOnly = studyProperties.getVideoOnlyCourseIds() != null
                        && studyProperties.getVideoOnlyCourseIds().contains(readyCourse.getCourseId());
                boolean skipTypesConfigured = studyProperties.getSkipJobTypes() != null
                        && !studyProperties.getSkipJobTypes().isEmpty();
                for (Job job : jobs) {
                    try {

                        // 跳过规则：
                        //   ① 全局 study.chaoxing.skip-job-types（如 workid = 做题/章节测验）
                        //   ② 命中 video-only-course-ids 的课程只保留 video
                        if ((skipTypesConfigured && studyProperties.getSkipJobTypes().contains(job.getType()))
                                || (videoOnly && !JobTypeConstant.VIDEO.equals(job.getType()))) {
                            System.out.println(Thread.currentThread().getName()
                                    + "[skip]跳过任务点[" + job.getType() + "]: "
                                    + readyCourse.getTitle() + " / " + job.getTitle());
                            continue;
                        }

                        switch (job.getType()) {

                            case JobTypeConstant.VIDEO:
                                boolean videoPassed = false;
                                boolean threw = false;
                                try {
                                    videoPassed = courseUtil.studyVideo(readyCourse, job, jobInfo.get(0), studyProperties.getSpeed(), "Video", log);
                                } catch (Exception e) {
                                    threw = true;
                                }
                                // 只有「抛异常」才换音频通道重试（保持原行为，避免失败视频耗时翻倍）
                                if (!videoPassed && threw) {
                                    try {
                                        videoPassed = courseUtil.studyVideo(readyCourse, job, jobInfo.get(0), studyProperties.getSpeed(), "Audio", log);
                                    } catch (Exception e) {
                                        videoPassed = false;
                                    }
                                }
                                // FIX: 老实现不看返回值。studyVideo 现在返回服务端裁决（isPassed），
                                // false 表示「上报被接受但超星没计分」，这种章节不能算刷完。
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
                                courseUtil.studyWork(readyCourse, job, jobInfo.get(0), log);
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
                    failedChapters++;
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
                // FIX: break 出来时任务会一直停在「队列中(2)」不动
                // （既不进 FINISHED 块，也没抛给上层 Runnable 的重试逻辑）。
                // 显式标成异常，队列状态才跟事实一致。
                task.setStatus(WkTaskStatusEnum.ABNORMAL.getCode());
                this.updateById(task);
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

        if (real != null) {
            int total = real.getOrDefault("total", 0);
            int done = real.getOrDefault("done", 0);
            int pending = real.getOrDefault("pending", 0);
            int noTask = real.getOrDefault("noTask", 0);
            // 真实完成度 = 已完成章节 / 有任务点的章节（本来就没任务点的章节不该拉低百分比）
            int denominator = Math.max(1, total - noTask);
            double realPercent = Math.min(100.0, done * 100.0 / denominator);

            if (pending == 0 && failedChapters == 0) {
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
                        pending, done, total, failedChapters));
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
                }
            }
            logQueue.offer(log);
            this.updateById(task);
            return;
        }

        // 拉不到课程页（网络/登录问题）→ 退回本地判断，
        // 且只敢在「确实遍历完 + 没有失败章节」时才宣布完成
        if (loopFinished && failedChapters == 0) {
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
                    pointIndex, totalChapters, failedChapters));
            task.setStatus(WkTaskStatusEnum.ABNORMAL.getCode());
            // 同上：顶到末尾但没刷完 → 指针归零，否则下一轮 0 秒扫完、死循环
            if (pointIndex >= totalChapters) {
                log.setCurrentChapterIndex(0);
            }
        }
        logQueue.offer(log);
        this.updateById(task);
    }
}