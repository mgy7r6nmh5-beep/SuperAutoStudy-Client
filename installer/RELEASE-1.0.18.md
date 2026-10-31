# SuperAutoStudy v1.0.18

发布日期：2026-10-31

这一版全部是**修 bug**，重点是让「刷课」这件事真的能从头跑到尾。
上一版有四个问题会互相掩护，看起来像是一个毛病，其实是四件不同的事。

---

## 修复

### 1. 刷课刷到一半突然不动了（最严重）

**现象**：界面还显示「进行中」，但进度条再也不动了。日志停在某一句
`开始学习视频` 之后就再没有新东西，后端进程活着、CPU 也不高，就是不干活。

**原因**：调度线程池用的是 `PriorityBlockingQueue`，但创建队列时**没给
`Comparator`**。队列为空时插入裸 `Runnable`（`scheduleNext()` 提交的
lambda）不会做比较，所以一直没暴露；等队列里已经有任务、再插入这个
lambda 时，`siftUp` 会拿它去跟 `PriorityTaskWrapper` 比大小 ——
lambda 没实现 `Comparable`，直接抛：

```
ClassCastException: com.tihai.service.superstar.impl.SuperStarTaskServiceImpl$$Lambda$1158
    cannot be cast to java.lang.Comparable
```

异常是在 `ThreadPoolExecutor.execute()` 里抛的，**不是**在任务体里，
所以不会被任务自己的 try/catch 接住 —— 它直接把 worker 线程打死了。
线程池核心线程数固定为 1（单课串行是硬约束），唯一的线程一死，
后面的课全部卡住，而且不会有任何报错弹窗。

**修法**：给 `PriorityTaskWrapper` 加一个静态比较器 `PRIORITY_ORDER`，
两处 `new PriorityBlockingQueue<>(...)` 都把它传进去。不是
`PriorityTaskWrapper` 的裸 `Runnable` 一律排在最后，不再参与类型转换。

### 2. 课程显示「进行中」，点进去一个任务点都没有

**现象**：总览里某门课一直是「进行中」，点「任务点」却是空的；
或者所有章节都刷完了，课程还挂在「进行中」下不来。

**原因**：数据库里两条记录的 `status` 不一致 ——
`th_wk_queue.status = 4`（已完成）但 `th_wk_log.status = 1`（进行中）。
上一版后端重启时如果正好被打断，就会留下这种「僵尸行」。

**修法**：两层保险。
- `StuckTaskRecover` 新增 `fixStaleLogs()`：后端启动时把
  「log 是进行中、但 queue 已完成」的行一次性纠正为已完成；
- 客户端 `status_of()` 增加兜底判断：`log` 已完成且
  `queue` 不是待处理时，直接按「已完成」显示，不再受脏数据影响。

### 3. 总览的「正在刷的视频」永远是空的

**现象**：后台日志里明明在刷，总览那张卡片一直是「暂无」。

**原因**：三个叠加的小问题。
1. 客户端读的是 `app/live.log`（logback 的 FILE appender），但
   `System.out` 里的刷课输出**只进** `app/boot.log`（stdout 重定向），
   两个文件内容不一样。原来优先读 `live.log`，自然读不到；
2. 首次读取的窗口是 200KB，会把**上一个进程**残留的
   `开始学习视频` 重放出来，做出一个假卡片；
3. 我自己加的半行裁剪逻辑用了 `if start > 0`，把每次正常增量读取的
   **第一整行**也裁掉了 —— 而播放位置日志 60 秒才来一条，等于全丢。

**修法**：优先读 `boot.log`；首读窗口收到 16KB 且只从文件尾部对齐到
换行处；半行裁剪只在「首读且从文件中间开始」时启用，正常增量读取不动。

### 4. 「最近完成的视频」只有本次开程序之后的

**现象**：昨天刷完的视频，今天重开程序就看不到了。

**原因**：这份列表只来自内存里的 `LiveTracker`，进程一退就没了。

**修法**：客户端启动后做一次历史回填 —— 扫一遍 `live.log` 和
`boot.log` 里所有 `视频已计分` 记录，用临时 tracker 只取「最近完成」
那一栏，再按 `(课程, 视频名)` 去重合并进来。

> 说明：视频级别的历史只到 **2026-10-30 14:42**。更早的记录被之前几次
> OTA 更新覆盖过，找不回来了。更早的课程级状态在 `th_wk_log` 里还在。

### 5. 窗口起不来时不再白等（顺带修的老问题）

**现象**：部分电脑（尤其新装的 Windows、或装在中文路径下）启动后
弹窗报 `failed to load ...\Python.Runtime.dll`，点确定程序就退出了，
其实数据库和后端都已经起好了。

**原因**：pywebview 靠 pythonnet 加载 .NET，机器上 .NET 版本不够、
或者路径里有中文导致传给 .NET 时编码坏掉，都会加载失败。

**修法**：`desktop.py` 新增 `fallback_to_browser()` —— 窗口起不来就
用系统浏览器打开 `http://127.0.0.1:3080`，服务继续在后台跑，
功能完全一样，不会让用户白等一场。

---

## 文档

- `使用说明.md` 重写。原来那份还在教用户双击
  `SuperAutoStudy 控制台.exe` —— 那是上一代客户端，现在根本没有这个文件。
  新版按当前界面（总览 / 课程队列 / 登录·选课 / 设置 / 日志）逐页讲。
- `README.md` 同步更新。

---

## 升级方式

- **已经装好的**：设置 → 检查更新，下载 **客户端补丁包**（约 54 KB）。
  这次后端 jar 也变了，所以补丁包装完还要再更一次**完整安装包**。
  数据库和刷课进度都会保留。
- **新装的**：直接用 `SuperAutoStudy-Setup-1.0.18.exe`。

---

## 校验

| 文件 | 大小 | SHA256 |
|---|---|---|
| `SuperAutoStudy-Setup-1.0.18.exe` | 109.6 MB | `0d5e9ee0a60fa2c8cf4f21a06c12fc4eab36a778dd5d6889de2400efa76dfefd` |
| `SuperAutoStudy-portable-1.0.18.zip` | 191.9 MB | `d93675598b4b105f8649ef42a4bd421c0e75a30deae9dca552c605912aad3474` |
| `SuperAutoStudy-client-1.0.18.zip` | 53.6 KB | `3a1dae8d93fc19f8a9cca0df0a5da717b7ce9cb62744bb7bbe644ea7ada492ed` |

便携包与安装包内容一致（339 MB 解压后，1512 个文件），
都自带 JDK 8 + MySQL 5.7 + Python 3.13，目标机不需要装任何运行时。
