package com.novelplayer.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(NovelPlayerProperties.class)
/**
 * 应用级配置入口，集中启用自定义配置属性绑定。
 */
public class ApplicationConfig {
}
