package com.tihai.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * @Copyright : DuanInnovator
 * @Description : 线程池配置
 * @Author : DuanInnovator
 * @CreateTime : 2025/3/2
 * @Link : <a href="https://github.com/DuanInnovator/SuperAutotudy">...</a>
 **/
@Data
@Configuration
@ConfigurationProperties(prefix = "custom.thread-pool")
public class ThreadPoolProperties {
    /**
     * 核心 / 最大线程数不再可配：学习线程固定为 {@link com.tihai.constant.StudyPolicy#LEARNING_THREADS}。
     * 这里只保留与并发度无关的池参数，并给出仓库内可用的默认值。
     */
    private int keepAlive = 60;
    private int queueCapacity = 64;
    private String threadNamePrefix = "priority-pool-";
    private boolean allowCoreThreadTimeout = false;
}