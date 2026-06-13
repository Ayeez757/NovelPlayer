package com.novelplayer.ai;

import com.novelplayer.application.generation.GenerationOptions;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;
import com.novelplayer.domain.script.ScriptDocument;

import java.util.List;

/*issue：
抽出 LlmJsonClient 底层工具。只保留一个核心能力：传入系统提示词 + 用户内容，返回标准 JSON 字符串。
逐个分段逻辑迁移到各自 Service，全权掌控 Prompt 文案、入参组装、JSON→实体解析、阶段业务校验。
 ai 层只做 HTTP 调用、异常捕获、限流、密钥鉴权。
 */
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
