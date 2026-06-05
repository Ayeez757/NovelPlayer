package com.novelplayer.application.generation;

import com.novelplayer.ai.ScriptAiClient;
import com.novelplayer.application.script.ScriptSchemaValidator;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;
import com.novelplayer.domain.script.ScriptDocument;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
/**
 * 剧本生成的高层管线入口。
 *
 * 当前最小可用版本背后只有一次模型调用，但保留这一层可以在后续平滑扩展为
 * 章节分析、故事圣经、场景规划、分场写作等多阶段流程。
 */
public class ScriptGenerationPipeline {

    private final ScriptAiClient scriptAiClient;
    private final ScriptSchemaValidator validator;

    public ScriptGenerationPipeline(ScriptAiClient scriptAiClient, ScriptSchemaValidator validator) {
        this.scriptAiClient = scriptAiClient;
        this.validator = validator;
    }

    public ScriptDocument generate(NovelProject project, List<NovelChapter> chapters, GenerationOptions options) {
        ScriptDocument document = scriptAiClient.generateScript(project, chapters, options);
        // 先校验再导出 YAML，避免无效角色/地点引用进入作者可下载结果。
        validator.validate(document);
        return document;
    }
}
