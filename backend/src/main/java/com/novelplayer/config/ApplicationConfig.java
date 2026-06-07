package com.novelplayer.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 应用级配置入口，集中启用自定义配置属性绑定和后台执行器。
 */
@Configuration
@EnableConfigurationProperties(NovelPlayerProperties.class)
@EnableAsync
public class ApplicationConfig {

    /**
     * 后台生成任务线程池。
     *
     * <p>该线程池负责承载一次完整生成任务的调度，不直接承载章节/场景级模型请求。</p>
     *
     * @return 生成任务执行器。
     */
    @Bean(name = "generationTaskExecutor")
    public Executor generationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("generation-job-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 阶段内并行任务线程池。
     *
     * <p>章节摘要和分场草稿会在这个线程池内做有界并行。线程数上限保持保守，避免长篇小说一次
     * 生成时把模型服务、数据库连接池或本机资源打满；队列满时使用调用方线程执行，形成自然背压。</p>
     *
     * @return 阶段内并行执行器。
     */
    @Bean(name = "generationStageTaskExecutor")
    public Executor generationStageTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("generation-stage-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(64);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
