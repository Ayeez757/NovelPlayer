package com.novelplayer.application.generation.prompt;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 章节摘要阶段提示词构建器。
 *
 * @see PromptMessages
 */
@Component
public class ChapterDigestPromptBuilder {

    private static final String SYSTEM_PROMPT_RESOURCE = "prompts/chapter-digest/system.md";
    private static final String USER_PROMPT_RESOURCE = "prompts/chapter-digest/user.md";

    /**
     * 已加载的提示词。
     */
    private final String systemPrompt;
    private final String userPromptTemplate;

    /**
     * 默认构造器。从 classpath 加载系统提示词和用户提示词模板文件。
     */
    public ChapterDigestPromptBuilder() {
        this(
                loadRequiredResource(SYSTEM_PROMPT_RESOURCE),
                loadRequiredResource(USER_PROMPT_RESOURCE)
        );
    }

    /**
     * 构造器（包级私有，主要用于测试）。
     *
     * @param systemPrompt 系统提示词内容，不能为空或空白。
     * @param userPromptTemplate 用户提示词模板内容，不能为空或空白。
     * @throws IllegalArgumentException 当任一参数为空或空白时抛出。
     */
    ChapterDigestPromptBuilder(String systemPrompt, String userPromptTemplate) {
        this.systemPrompt = normalizeTemplate(systemPrompt, "章节摘要系统提示词");
        this.userPromptTemplate = normalizeTemplate(userPromptTemplate, "章节摘要用户提示词");
    }

    /**
     * 构建完整的提示词消息对象。
     *
     * @param inputJson 章节摘要阶段的输入 JSON 数据（通常包含项目信息、章节内容和生成选项）。
     * @return 包含系统提示词和用户提示词的 {@link PromptMessages} 对象。
     * @throws NullPointerException 当输入 JSON 为 null 时抛出。
     * @throws IllegalStateException 当用户提示词模板格式化失败时抛出。
     */
    public PromptMessages build(String inputJson) {
        Objects.requireNonNull(inputJson, "输入 JSON 数据不能为空");
        return new PromptMessages(systemPrompt, userPromptTemplate.formatted(inputJson));
    }

    /**
     * 从 classpath 加载必需的资源文件。
     *
     * @param path 资源文件路径。
     * @return 资源文件的文本内容（UTF-8 编码）。
     * @throws IllegalStateException 当资源文件不存在或读取失败时抛出。
     */
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

    /**
     * 规范化模板字符串。
     *
     * @param value 待规范化的字符串。
     * @param name 字符串名称，用于错误信息提示。
     * @return 规范化后的字符串。
     * @throws IllegalArgumentException 当字符串为空或空白时抛出。
     */
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