package com.tihai.queue;


import java.util.Comparator;

/**
 * @Copyright : DuanInnovator
 * @Description :
 * @Author : DuanInnovator
 * @CreateTime : 2025/3/1
 * @Link : <a href="https://github.com/DuanInnovator/SuperAutotudy">...</a>
 **/

public class PriorityTaskWrapper implements Runnable, Comparable<PriorityTaskWrapper> {
    private final Runnable realTask;
    private int priority;
    private final String taskId;

    /**
     * 线程池队列的排序规则。
     *
     * FIX(2026-10-31)：PriorityBlockingQueue 创建时没给 Comparator，只能靠元素自己
     * 实现 Comparable。但池子里不止有 PriorityTaskWrapper —— scheduleNext() 提交的是
     * 裸 lambda（this::startChaoxingTask），登录那边以前也提交过 CompletableFuture。
     * 队列非空时一 insert 就走 siftUpComparable，直接抛：
     *     ClassCastException: SuperStarTaskServiceImpl$$Lambda$xxx cannot be cast to Comparable
     * 异常从 ThreadPoolExecutor.execute 抛到 worker 线程外，线程当场死掉，
     * scheduleNext 没成功 = 后面的课永远不会被推进，表现就是「刷着刷着不动了」。
     *
     * 这里兜一层：不是 PriorityTaskWrapper 的按最低优先级算，排到最后，不再比较崩。
     */
    public static final Comparator<Runnable> PRIORITY_ORDER =
            (a, b) -> Integer.compare(priorityOf(a), priorityOf(b));

    private static int priorityOf(Runnable task) {
        if (task instanceof PriorityTaskWrapper) {
            return ((PriorityTaskWrapper) task).getPriority();
        }
        return Integer.MAX_VALUE;
    }

    public PriorityTaskWrapper(Runnable realTask, int priority, String taskId) {
        this.realTask = realTask;
        this.priority = priority;
        this.taskId = taskId;
    }

    @Override
    public void run() {
        realTask.run();
    }

    @Override
    public int compareTo(PriorityTaskWrapper other) {
        return Integer.compare(this.priority,other.priority); // 降序排列
    }

    public void decreasePriority(int delta) {
        this.priority += delta;
    }

    // Getters
    public int getPriority() { return priority; }
    public String getTaskId() { return taskId; }
}