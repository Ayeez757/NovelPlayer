package com.novelplayer.application.generation;

import com.novelplayer.ai.ScriptAiClient;
import com.novelplayer.application.script.ScriptSchemaValidator;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;
import com.novelplayer.domain.script.ScriptDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 剧本生成的高层管线入口。
 *
 * 当前最小可用版本背后只有一次模型调用，但保留这一层可以在后续平滑扩展为
 * 章节分析、故事圣经、场景规划、分场写作等多阶段流程。
 */
@Service
public class ScriptGenerationPipeline {

    private static final Logger log = LoggerFactory.getLogger(ScriptGenerationPipeline.class);

    private final ScriptAiClient scriptAiClient;
    private final ScriptSchemaValidator validator;

    /**
     * 注入模型客户端和剧本结构校验器。
     *
     * @param scriptAiClient AI 生成客户端。
     * @param validator 剧本文档校验器。
     */
    public ScriptGenerationPipeline(ScriptAiClient scriptAiClient, ScriptSchemaValidator validator) {
        this.scriptAiClient = scriptAiClient;
        this.validator = validator;
    }

    /**
     * 生成剧本文档并立即做结构校验，确保调用方拿到的始终是可导出的结果。
     *
     * @param project 小说改编项目。
     * @param chapters 已拆分并持久化的章节。
     * @param options 改编控制选项。
     * @return 已通过校验的剧本文档。
     */
    public ScriptDocument generate(NovelProject project, List<NovelChapter> chapters, GenerationOptions options) {
        log.info("Script generation pipeline started projectId={} chapterCount={} format={} tone={}",
                project.getId(), chapters.size(), options.format(), options.tone());
        ScriptDocument document = scriptAiClient.generateScript(project, chapters, options);
        log.info("AI script document returned projectId={} characterCount={} locationCount={} sceneCount={}",
                project.getId(), safeSize(document.characters()), safeSize(document.locations()), safeSize(document.scenes()));
        // 先校验再导出 YAML，避免无效角色/地点引用进入作者可下载结果。
        validator.validate(document);
        log.info("Script document validated projectId={} schemaVersion={} sceneCount={}",
                project.getId(), document.schemaVersion(), safeSize(document.scenes()));
        return document;
    }

    /**
     * 安全读取列表大小，用于生成阶段日志。
     *
     * @param values 待统计列表。
     * @return 列表大小；列表为空引用时返回 -1。
     */
    private static int safeSize(List<?> values) {
        return values == null ? -1 : values.size();
    }
}
