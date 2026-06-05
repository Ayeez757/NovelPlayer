package com.novelplayer.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelplayer.application.generation.GenerationOptions;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;
import com.novelplayer.domain.script.ScriptDocument;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "novel-player.generation", name = "mock-ai", havingValue = "false")
/**
 * 基于 Spring AI 兼容 OpenAI 接口客户端的 DeepSeek 实现。
 */
public class DeepSeekScriptAiClient implements ScriptAiClient {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public DeepSeekScriptAiClient(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @Override
    public ScriptDocument generateScript(NovelProject project, List<NovelChapter> chapters, GenerationOptions options) {
        String prompt = buildPrompt(project, chapters, options);
        String content = chatClient.prompt()
                .system("""
                        你是一名资深中文剧本改编助手。
                        只返回一个符合要求结构的合法 JSON 对象。
                        不要使用 Markdown 代码块包裹 JSON。
                        """)
                .user(prompt)
                .call()
                .content();

        try {
            // 模型只返回 JSON；结构校验和 YAML 导出由后端负责。
            return objectMapper.readValue(content, ScriptDocument.class);
        } catch (Exception exception) {
            throw new IllegalStateException("DeepSeek 返回内容不是合法的剧本文档 JSON 对象。", exception);
        }
    }

    private String buildPrompt(NovelProject project, List<NovelChapter> chapters, GenerationOptions options) {
        // 提示词与剧本文档结构保持一致，降低解析和校验的不确定性。
        StringBuilder builder = new StringBuilder();
        builder.append("请将以下中文小说章节改编为结构化剧本初稿。\n");
        builder.append("作品标题：").append(project.getTitle()).append("\n");
        builder.append("剧本类型：").append(options.format()).append("\n");
        builder.append("整体风格：").append(options.tone()).append("\n");
        builder.append("对白密度：").append(options.dialogueDensity()).append("/100\n");
        builder.append("旁白保留度：").append(options.narrationRetention()).append("/100\n\n");
        builder.append("""
                必须返回如下 JSON 结构：
                {
                  "schemaVersion": "1.0",
                  "metadata": {"title": "...", "language": "zh-CN", "sourceChapterCount": 3, "generatedAt": "2026-06-05T12:00:00+08:00"},
                  "adaptation": {"format": "web_drama", "tone": "suspense", "logline": "...", "themes": ["..."]},
                  "characters": [{"id": "char_001", "name": "...", "aliases": [], "role": "protagonist", "goal": "...", "traits": [], "voice": "..."}],
                  "locations": [{"id": "loc_001", "name": "...", "type": "interior", "description": "..."}],
                  "scenes": [{"id": "scene_001", "title": "...", "sourceChapters": [1], "locationId": "loc_001", "timeOfDay": "night", "characters": ["char_001"], "dramaticPurpose": "...", "summary": "...", "blocks": [{"type": "action", "text": "..."}, {"type": "dialogue", "speakerId": "char_001", "text": "..."}]}],
                  "revisionNotes": ["..."]
                }

                生成约束：
                - 人物、地点和场景必须使用稳定编号。
                - 每个场景都必须填写 sourceChapters。
                - 每个对白块都必须填写 speakerId。
                - 尽量把内心独白转化为可表演的动作或对白。
                - 必须保留所有章节中的主要冲突。

                原文章节：
                """);

        for (NovelChapter chapter : chapters) {
            builder.append("\n## 第 ").append(chapter.getChapterIndex()).append(" 章：")
                    .append(chapter.getTitle()).append("\n");
            builder.append(chapter.getContent()).append("\n");
        }
        return builder.toString();
    }
}
