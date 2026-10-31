package com.tihai.constant;

/**
 * @Copyright : DuanInnovator
 * @Description : 重试次数
 * @Author : DuanInnovator
 * @CreateTime : 2025/3/2
 * @Link : <a href="https://github.com/DuanInnovator/SuperAutotudy">...</a>
 **/
public class RetryConstant {
    /**
     * 自动重试上限。与 {@link StudyPolicy#MAX_AUTO_RETRY} 保持一致：
     * 全系统只有这一个上限，不再同时存在 3 和 5 两套。
     */
    public static final int DEFAULT_RETRY_COUNT = StudyPolicy.MAX_AUTO_RETRY;

    /**
     * retry_count 可能为 NULL（老库），统一做空值安全判断。
     */
    public static boolean reachedLimit(Integer retryCount) {
        int retry = retryCount == null ? 0 : retryCount;
        return retry >= StudyPolicy.MAX_AUTO_RETRY;
    }
}

