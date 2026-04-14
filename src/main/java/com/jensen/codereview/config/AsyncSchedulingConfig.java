/*
 * All rights Reserved, Designed By Jensen
 * @Title:  AsyncSchedulingConfig.java
 * @Package com.jensen.codereview.config
 * @author: Jensen
 * @date:   2026/4/14 15:17
 * @version V1.0
 */
package com.jensen.codereview.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * @ClassName AsyncSchedulingConfig
 * @Description 异步调度配置
 * @Author Jensen
 * @Date 2026/4/14 14:42
 */
@Slf4j
@EnableAsync
@Configuration
public class AsyncSchedulingConfig {

    /**
     * 核心线程数
     */
    @Value("${code-review.thread-pool.core-size:20}")
    private int corePoolSize;

    /**
     * 最大线程数
     */
    @Value("${code-review.thread-pool.max-size:50}")
    private int maxPoolSize;

    /**
     * 队列容量
     */
    @Value("${code-review.thread-pool.queue-capacity:200}")
    private int queueCapacity;

    /**
     * 自定义异步任务线程池，用于并行执行 AI Skills
     */
    @Bean("asyncTaskExecutor")
    public Executor asyncTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("ai-skill-");
        // 拒绝策略：由调用线程处理，避免任务丢弃
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        
        log.info("AI Skill 线程池初始化: core={}, max={}, queue={}", 
                corePoolSize, maxPoolSize, queueCapacity);
        return executor;
    }

    /**
     * 自定义定时任务线程池
     */
    @Bean("taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(10);
        scheduler.setThreadNamePrefix("scheduled-task-");
        scheduler.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return scheduler;
    }
}
