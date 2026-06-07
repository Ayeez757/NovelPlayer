package com.novelplayer.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelplayer.application.generation.GenerationOptions;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;
import com.novelplayer.domain.script.ScriptDocument;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 Spring AI 兼容 OpenAI 接口客户端的 DeepSeek 实现。
 *
 * 只有关闭 mock-ai 时才注册该组件，避免本地无密钥开发时误触发真实模型调用。
 */
@Component
@ConditionalOnProperty(prefix = "novel-player.generation", name = "mock-ai", havingValue = "false")
public class DeepSeekScriptAiClient implements ScriptAiClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekScriptAiClient.class);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final ScriptPromptBuilder promptBuilder;
    private final DeepSeekChatOptionsFactory chatOptionsFactory;

    /**
     * 创建真实模型客户端。
     *
     * @param chatClientBuilder Spring AI 聊天客户端构建器。
     * @param objectMapper JSON 反序列化器。
     * @param promptBuilder 剧本提示词构建器。
     */
    public DeepSeekScriptAiClient(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper,
                                  ScriptPromptBuilder promptBuilder,
                                  DeepSeekChatOptionsFactory chatOptionsFactory) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
        this.promptBuilder = promptBuilder;
        this.chatOptionsFactory = chatOptionsFactory;
    }

    /**
     * 调用 DeepSeek 兼容接口生成剧本文档。
     *
     * @param project 小说改编项目。
     * @param chapters 按章节顺序排列的原文。
     * @param options 改编控制选项。
     * @return 模型返回并解析后的剧本文档。
     */
    @Override
    public ScriptDocument generateScript(NovelProject project, List<NovelChapter> chapters, GenerationOptions options) {
        ScriptPrompt prompt = promptBuilder.build(project, chapters, options);
        long startedAt = System.nanoTime();
        log.info("Calling DeepSeek projectId={} chapterCount={} systemPromptLength={} userPromptLength={} hasAdditionalInstructions={}",
                project.getId(), chapters.size(), prompt.systemPrompt().length(), prompt.userPrompt().length(),
                options.hasAdditionalInstructions());
        // system prompt 只约束输出协议，具体作品信息和章节内容放在 user prompt。
        String content = chatOptionsFactory.apply(chatClient.prompt()
                        .system(prompt.systemPrompt())
                        .user(prompt.userPrompt()))
                .call()
                .content();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("DeepSeek response received projectId={} responseLength={} elapsedMs={}",
                project.getId(), content == null ? 0 : content.length(), elapsedMs);

        try {
            // 模型只返回 JSON；结构校验和 YAML 导出由后端负责。
            return objectMapper.readValue(content, ScriptDocument.class);
        } catch (Exception exception) {
            log.warn("DeepSeek response parse failed projectId={} responseLength={}",
                    project.getId(), content == null ? 0 : content.length(), exception);
            throw new IllegalStateException("DeepSeek 返回内容不是合法的剧本文档 JSON 对象。", exception);
        }
    }
}
