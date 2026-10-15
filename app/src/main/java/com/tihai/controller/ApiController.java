package com.tihai.controller;

import com.tihai.common.ChapterPoint;
import com.tihai.common.CoursePoint;
import com.tihai.common.Job;
import com.tihai.common.R;
import com.tihai.domain.chaoxing.WkUser;
import com.tihai.dubbo.pojo.course.Course;
import com.tihai.service.superstar.SuperStarUserService;
import com.tihai.utils.CourseUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 客户端只读接口。
 *
 * <p>给「SuperAutoStudy 客户端」用的补充数据源：<b>只读，不改变任何刷课状态</b>。
 * 登录（POST /chaoxing/course）、加课（POST /chaoxing）、启动队列（GET /chaoxing）
 * 仍走 {@link SuperStarController} 与 {@link SuperStarCourseController} 的既有接口。
 *
 * <p><b>会话隔离</b>：所有请求都走 {@link CourseUtil#forkSession()}，不碰
 * Spring 单例上的 cookie —— 否则会和正在跑的刷课流程互相覆盖账号。
 *
 * <p><b>异步扫描</b>：一门课有几十上百章，逐章拉 cards 页要好几分钟。所以
 * {@code /api/points} 首次调用只<b>触发</b>后台扫描并立刻返回
 * {@code scanning=true}，客户端轮询即可看到 {@code scannedChapters/totalChapters}
 * 的实时进度；扫完写进缓存，5 分钟内重复请求直接命中。
 *
 * @author SuperAutoStudy Client
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    @Autowired
    private CourseUtil courseUtil;

    @Autowired
    private SuperStarUserService userService;

    /** 课程列表缓存：key = account，value = [时间戳, 数据] */
    private static final Map<String, Object[]> COURSES_CACHE = new ConcurrentHashMap<>();
    private static final long COURSES_TTL_MS = 60_000L;

    /** 任务点扫描结果：key = account|courseId */
    private static final Map<String, ScanState> SCANS = new ConcurrentHashMap<>();
    private static final long POINTS_TTL_MS = 5 * 60_000L;
    /** 扫描失败后多久允许重试 */
    private static final long POINTS_ERROR_RETRY_MS = 15_000L;
    /**
     * 扫描卡死兜底：一个扫描跑超过这个时长还没结束，就认为它的 worker 已经
     * 静默死亡（曾出现过 running=true 但线程池 worker 全空闲、st.running 永真，
     * 之后所有 refresh 都被挡在「扫描中」，界面永久卡住，只能重启后端）。
     * 到期后允许重新提交，旧线程即使复活也只是写被替换掉的孤儿对象，无害。
     */
    private static final long POINTS_STUCK_MS = 10 * 60_000L;

    /**
     * 拉任务点的并发度。刻意压到 2：{@code getJobList} 每章要发 1~3 次 cards 请求
     * （num=0/1/2）且占位页还会重试，并发太高容易被超星风控（9010）。
     */
    private static final int POINTS_CONCURRENCY = 2;

    /** 后台扫描线程池。扫描本身内部还会再开小池，这里只需容纳「同时几门课在扫」。 */
    private static final ExecutorService SCAN_POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "api-points-scan");
        t.setDaemon(true);
        return t;
    });

    /**
     * 账号下的课程列表（用库里已保存的 cookie，<b>不重新登录</b>）。
     *
     * <p>与 {@code POST /chaoxing/course} 的区别：那个接口每次都真的去超星登录一次
     * （消耗额度、有风控风险）；这个只用本地 cookie，适合客户端频繁刷新用。
     *
     * @param account 超星账号
     * @return data = List&lt;Course&gt;，每项含 courseId / title / clazzId / cpi / teacher
     */
    /**
     * 客户端用它确认「该读哪个部署目录」。
     *
     * 同一台机器上可能同时存在两份部署（例如开发实例 + 便携包），各有各的
     * app/live.log。便携包里的客户端若只认自己所在目录，就会读到包内那份
     * 空白日志，界面显示「没有在刷视频」——哪怕另一个实例正刷得热火朝天。
     * 所以让后端自报家门，客户端据此对齐。
     *
     * @return data = {root: 部署根目录, cwd: 进程工作目录}
     */
    @GetMapping("/where")
    public R where() {
        File dir = new File(System.getProperty("user.dir"));
        File root = dir;
        // 从工作目录逐级向上，找到含 mysql57 的那一层（jar 在 <root>/app/target/）
        for (int i = 0; i < 5 && root != null; i++) {
            if (new File(root, "mysql57").isDirectory()) {
                break;
            }
            root = root.getParentFile();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("root", root == null ? dir.getAbsolutePath() : root.getAbsolutePath());
        data.put("cwd", dir.getAbsolutePath());
        return R.ok().setData(data);
    }

    @GetMapping("/courses")
    public R courses(@RequestParam("account") String account) {
        WkUser user = userService.getUserByAccount(account);
        if (user == null || !StringUtils.hasLength(user.getCookies())) {
            return R.error(401, "本地没有该账号的登录态，请先登录");
        }
        Object[] cached = COURSES_CACHE.get(account);
        if (cached != null && System.currentTimeMillis() - (Long) cached[0] < COURSES_TTL_MS) {
            return R.ok().setData(cached[1]);
        }
        try {
            CourseUtil cu = courseUtil.forkSession();
            cu.setCookies(user.getCookies());
            cu.setAccount(account);
            List<Course> list = cu.getCourseList();
            COURSES_CACHE.put(account, new Object[]{System.currentTimeMillis(), list});
            return R.ok().setData(list);
        } catch (Exception e) {
            return R.error(500, "拉取课程列表失败: " + e.getMessage());
        }
    }

    /**
     * 某门课的<b>全部任务点</b>（含已完成的），供客户端画「每条视频的进度」。
     *
     * <p>返回的每一项都带 {@code passed}（超星判定的 isPassed）、
     * {@code playTime}（已播放毫秒）、{@code duration}（附件时长秒），
     * 客户端据此渲染每一条视频的状态。
     *
     * <p>本接口<b>非阻塞</b>：
     * <ul>
     *   <li>首次调用 → 启动后台扫描，返回 {@code scanning=true, scannedChapters=0}；</li>
     *   <li>扫描中 → 返回 {@code scanning=true} 与当前已扫章数、已解析到的 jobs（增量可见）；</li>
     *   <li>扫完 → 返回 {@code scanning=false, cached=true}，之后 5 分钟内直接命中。</li>
     * </ul>
     *
     * @param account  超星账号
     * @param courseId 课程 ID
     * @param refresh  传 {@code true} 时忽略缓存强制重扫
     * @return data = { scanning, cached, error, courseId, courseName, clazzId, cpi,
     *                  jobs:[{chapterId, chapter, type, name, jobId, duration, passed, playTime}],
     *                  scannedChapters, totalChapters }
     */
    @GetMapping("/points")
    public R points(@RequestParam("account") String account,
                    @RequestParam("courseId") String courseId,
                    @RequestParam(value = "refresh", required = false) String refresh) {
        WkUser user = userService.getUserByAccount(account);
        if (user == null || !StringUtils.hasLength(user.getCookies())) {
            return R.error(401, "本地没有该账号的登录态，请先登录");
        }

        final String key = account + "|" + courseId;
        final boolean force = "true".equalsIgnoreCase(refresh) || "1".equals(refresh);
        final long now = System.currentTimeMillis();

        ScanState st = SCANS.get(key);
        if (st != null) {
            if (st.running) {
                if (now - st.startedAt <= POINTS_STUCK_MS) {
                    return R.ok().setData(snapshot(st, true, false));
                }
                // 超期未结束 = 卡死，放行到下面的重提交逻辑
            } else {
                long age = now - st.finishedAt;
                if (st.error == null && !force && age < POINTS_TTL_MS) {
                    return R.ok().setData(snapshot(st, false, true));
                }
                if (st.error != null && !force && age < POINTS_ERROR_RETRY_MS) {
                    return R.ok().setData(snapshot(st, false, false));
                }
            }
        }

        // 单飞：同一门课同一时刻只跑一个扫描（卡死超期的旧扫描允许被顶掉重跑）
        synchronized (SCANS) {
            st = SCANS.get(key);
            boolean stuck = st != null && st.running
                    && now - st.startedAt > POINTS_STUCK_MS;
            if (st == null || (!st.running && (force || expired(st, now))) || stuck) {
                st = new ScanState();
                SCANS.put(key, st);
                final ScanState target = st;
                final WkUser u = user;
                SCAN_POOL.submit(() -> doScan(target, u, account, courseId));
            }
        }
        return R.ok().setData(snapshot(st, true, false));
    }

    private static boolean expired(ScanState st, long now) {
        if (st.finishedAt == 0L) {
            return true;
        }
        long age = now - st.finishedAt;
        return st.error != null ? age >= POINTS_ERROR_RETRY_MS : age >= POINTS_TTL_MS;
    }

    /** 组装返回给客户端的快照。jobs 复制一份，避免与扫描线程并发修改。 */
    private static Map<String, Object> snapshot(ScanState st, boolean scanning, boolean cached) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scanning", scanning);
        m.put("cached", cached);
        m.put("error", st.error);
        m.put("scannedChapters", st.scanned);
        m.put("totalChapters", st.total);
        if (st.meta != null) {
            m.putAll(st.meta);
        }
        synchronized (st.jobs) {
            m.put("jobs", new ArrayList<>(st.jobs));
        }
        return m;
    }

    /** 真正的扫描逻辑，跑在后台线程里，边扫边把结果塞进 {@code st}。 */
    private void doScan(ScanState st, WkUser user, String account, String courseId) {
        st.running = true;
        st.startedAt = System.currentTimeMillis();
        CourseUtil cu = courseUtil.forkSession();
        try {
            cu.setCookies(user.getCookies());
            cu.setAccount(account);

            Course target = null;
            List<Course> courses = cu.getCourseList();
            if (courses != null) {
                for (Course c : courses) {
                    if (courseId.equals(c.getCourseId())) {
                        target = c;
                        break;
                    }
                }
            }
            if (target == null) {
                st.error = "账号下没有找到课程 " + courseId;
                return;
            }

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("courseId", courseId);
            meta.put("courseName", target.getTitle());
            meta.put("clazzId", target.getClazzId());
            meta.put("cpi", target.getCpi());
            st.meta = meta;

            List<CoursePoint> cpList = cu.getCoursePoint(
                    courseId, target.getClazzId(), target.getCpi());
            // 「章节 → 未完成任务点数」：只对未完成的章节去拉明细
            Map<String, Integer> pendingMap = cu.getChapterPending(
                    courseId, target.getClazzId(), target.getCpi());

            if (cpList == null || cpList.isEmpty() || cpList.get(0).getPoints() == null) {
                st.total = 0;
                return;
            }
            List<ChapterPoint> all = cpList.get(0).getPoints();
            st.total = all.size();

            List<ChapterPoint> todo = new ArrayList<>();
            for (ChapterPoint ch : all) {
                Integer pend = (pendingMap == null) ? null : pendingMap.get(ch.getId());
                if (pend != null && pend == 0) {
                    st.scanned++;   // 已完成 / 无任务点，不必拉明细
                } else {
                    todo.add(ch);
                }
            }

            if (!todo.isEmpty()) {
                final Course tgt = target;
                final String cid = courseId;
                ExecutorService pool = Executors.newFixedThreadPool(
                        Math.min(POINTS_CONCURRENCY, todo.size()));
                try {
                    List<Future<List<Job>>> futures = new ArrayList<>();
                    for (ChapterPoint ch : todo) {
                        futures.add(pool.submit(() ->
                                cu.getJobList(tgt.getClazzId(), cid,
                                        tgt.getCpi(), ch.getId(), true).getFirst()));
                    }
                    for (int i = 0; i < todo.size(); i++) {
                        ChapterPoint ch = todo.get(i);
                        try {
                            List<Job> jobs = futures.get(i).get(120, TimeUnit.SECONDS);
                            if (jobs != null) {
                                for (Job j : jobs) {
                                    Map<String, Object> m = new LinkedHashMap<>();
                                    m.put("chapterId", ch.getId());
                                    m.put("chapter", ch.getTitle());
                                    m.put("type", j.getType());
                                    m.put("name", j.getName() != null ? j.getName() : j.getTitle());
                                    m.put("jobId", j.getJobId());
                                    m.put("duration", j.getAttDuration());
                                    m.put("passed", j.getPassed());
                                    m.put("playTime", j.getPlayTime());
                                    synchronized (st.jobs) {
                                        st.jobs.add(m);
                                    }
                                }
                            }
                        } catch (Exception ignore) {
                            // 单章失败不影响整门课
                        }
                        st.scanned++;
                    }
                } finally {
                    pool.shutdownNow();
                }
            }
        } catch (Throwable e) {
            st.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            st.running = false;
            st.finishedAt = System.currentTimeMillis();
        }
    }

    /** 一次任务点扫描的共享状态。字段都是 volatile / 同步容器，供 HTTP 线程读。 */
    private static final class ScanState {
        volatile boolean running;
        volatile int scanned;
        volatile int total;
        volatile long startedAt;
        volatile long finishedAt;
        volatile String error;
        volatile Map<String, Object> meta;
        final List<Map<String, Object>> jobs = Collections.synchronizedList(new ArrayList<>());
    }
}
