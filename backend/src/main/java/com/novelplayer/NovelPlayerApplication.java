package com.novelplayer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * NovelPlayer 后端启动类，负责引导 Spring Boot 应用上下文。
 */
@SpringBootApplication
public class NovelPlayerApplication {

    /**
     * 应用入口。具体使用本地 MySQL 还是测试/容器 MySQL，由 Spring 配置环境决定。
     *
     * @param args 命令行参数，交给 Spring Boot 解析。
     */
    public static void main(String[] args) {
        SpringApplication.run(NovelPlayerApplication.class, args);
    }
}
