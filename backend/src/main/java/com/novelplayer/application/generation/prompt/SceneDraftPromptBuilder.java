package com.novelplayer.application.generation.prompt;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 分场草稿阶段提示词构建器。
 * @see PromptMessages
 */
@Component
public class SceneDraftPromptBuilder {

    private static final String SYSTEM_PROMPT_RESOURCE = "prompts/scene-draft/system.md";
    private static final String USER_PROMPT_RESOURCE = "prompts/scene-draft/user.md";
    private final String systemPrompt;
    private final String userPromptTemplate;

    public SceneDraftPromptBuilder() {
        this(
                loadRequiredResource(SYSTEM_PROMPT_RESOURCE),
                loadRequiredResource(USER_PROMPT_RESOURCE)
        );
    }

    SceneDraftPromptBuilder(String systemPrompt, String userPromptTemplate) {
        this.systemPrompt = normalizeTemplate(systemPrompt, "分场草稿系统提示词");
        this.userPromptTemplate = normalizeTemplate(userPromptTemplate, "分场草稿用户提示词");
    }

    /**
     * 构建完整的提示词消息对象。
     */
    public PromptMessages build(String inputJson) {
        Objects.requireNonNull(inputJson, "输入 JSON 数据不能为空");
        return new PromptMessages(systemPrompt, userPromptTemplate.formatted(inputJson));
    }

    // 从 classpath 加载必需的资源文件
    private static String loadRequiredResource(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                throw new IllegalStateException("提示词资源文件不存在：" + path);
            }
            try (InputStream inputStream = resource.getInputStream()) {
                return StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("加载提示词资源文件失败：" + path, exception);
        }
    }

    // 规范化模板字符串，去除首尾空白字符，并确保字符串不为空或空白
    private static String normalizeTemplate(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空或空白");
        }
        return value.strip();
    }

    /**
     * 提示词消息记录类。
     */
    public record PromptMessages(String systemPrompt, String userPrompt) {
    }
}