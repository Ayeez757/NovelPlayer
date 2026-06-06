package com.novelplayer.ai;

import com.novelplayer.application.generation.GenerationOptions;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;
import com.novelplayer.domain.script.ScriptDocument;

import java.util.List;

/**
 * 模型提供方边界接口，让生成管线不直接依赖 DeepSeek 或模拟实现。
 */
public interface ScriptAiClient {

    /**
     * 根据已拆分并持久化的章节生成结构化剧本文档。
     *
     * 实现类只负责产出 Java 可解析的结构，引用关系和 YAML 格式由应用层统一处理。
     *
     * @param project 小说改编项目。
     * @param chapters 按顺序持久化的小说章节。
     * @param options 改编控制选项。
     * @return 结构化剧本文档。
     */
    ScriptDocument generateScript(NovelProject project, List<NovelChapter> chapters, GenerationOptions options);
}
