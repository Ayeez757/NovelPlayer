package com.novelplayer.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 应用级配置入口，集中启用自定义配置属性绑定。
 */
@Configuration
@EnableConfigurationProperties(NovelPlayerProperties.class)
@EnableAsync
public class ApplicationConfig {

    /**
     * 后台生成任务线程池。
     *
     * <p>生成任务通常耗时较长，使用独立线程池可以避免占用 Web 请求线程；
     * 队列和线程数保持保守，避免本地或小规格部署时同时触发过多模型调用。</p>
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
        executor.initialize();
        return executor;
    }
}
