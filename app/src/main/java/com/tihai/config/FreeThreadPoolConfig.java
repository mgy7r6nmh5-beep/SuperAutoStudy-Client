package com.tihai.config;

import com.tihai.constant.StudyPolicy;
import com.tihai.factory.CustomThreadFactory;
import com.tihai.factory.PriorityRejectPolicy;
import com.tihai.properties.ThreadPoolProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @Copyright : DuanInnovator
 * @Description : 自定义线程池
 * @Author : DuanInnovator
 * @CreateTime : 2025/3/1
 * @Link : <a href="https://github.com/DuanInnovator/SuperAutotudy">...</a>
 **/
@Configuration
@Slf4j
public class FreeThreadPoolConfig {

    @Bean
    public ThreadPoolExecutor customThreadPool(ThreadPoolProperties config) {

        // 单课串行是硬约束：核心 / 最大线程都固定为 1，不接受外部覆盖。
        int threads = StudyPolicy.LEARNING_THREADS;
        log.info("当前学习线程数固定为:{}", threads);
        // 创建优先级阻塞队列
        BlockingQueue<Runnable> queue = new PriorityBlockingQueue<>(config.getQueueCapacity());

        // 构建线程池
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threads,
                threads,
                config.getKeepAlive(),
                TimeUnit.SECONDS,
                queue,
                new CustomThreadFactory(config.getThreadNamePrefix()),
                new PriorityRejectPolicy()
        );

        // 允许核心线程超时
        executor.allowCoreThreadTimeOut(config.isAllowCoreThreadTimeout());

        return executor;
    }

    @Bean
    public TaskExecutor taskExecutor(ThreadPoolExecutor customThreadPool) {
        return new ConcurrentTaskExecutor(customThreadPool);
    }
}