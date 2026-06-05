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

    ScriptDocument generateScript(NovelProject project, List<NovelChapter> chapters, GenerationOptions options);
}
