package com.novelplayer.ai;

import com.novelplayer.application.generation.GenerationOptions;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;
import com.novelplayer.domain.script.ScriptDocument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "novel-player.generation", name = "mock-ai", havingValue = "true", matchIfMissing = true)
/**
 * 稳定可重复的本地模拟实现，用于没有 DeepSeek 密钥时的演示、测试和开发。
 */
public class MockScriptAiClient implements ScriptAiClient {

    @Override
    public ScriptDocument generateScript(NovelProject project, List<NovelChapter> chapters, GenerationOptions options) {
        // 模拟结果也必须符合剧本结构约定，才能端到端验证界面、校验和 YAML 导出。
        List<ScriptDocument.CharacterProfile> characters = List.of(
                new ScriptDocument.CharacterProfile("char_001", "主角", List.of("她", "他"), "protagonist",
                        "推动故事进入关键冲突", List.of("敏感", "克制", "行动力强"), "短句为主，情绪压在动作里"),
                new ScriptDocument.CharacterProfile("char_002", "对手", List.of(), "antagonist",
                        "隐藏真相并制造阻力", List.of("冷静", "强势"), "语气平稳，常用反问")
        );

        List<ScriptDocument.LocationProfile> locations = List.of(
                new ScriptDocument.LocationProfile("loc_001", "核心场景", "interior", "从原文章节中抽象出的主要冲突发生地")
        );

        List<ScriptDocument.Scene> scenes = chapters.stream()
                .map(chapter -> toScene(chapter, characters.get(0), characters.get(1)))
                .toList();

        return new ScriptDocument(
                "1.0",
                new ScriptDocument.ScriptMetadata(project.getTitle(), "zh-CN", chapters.size(), OffsetDateTime.now()),
                new ScriptDocument.Adaptation(options.format(), options.tone(),
                        "主角在连续事件中发现隐藏线索，并被迫做出选择。",
                        List.of("选择", "真相", "关系裂变")),
                characters,
                locations,
                scenes,
                List.of("当前为本地模拟初稿，用于无模型密钥情况下验证产品闭环。", "后续接入 DeepSeek 后会生成更贴合原文的场景和对白。")
        );
    }

    private ScriptDocument.Scene toScene(NovelChapter chapter, ScriptDocument.CharacterProfile protagonist,
                                         ScriptDocument.CharacterProfile antagonist) {
        // 摘要刻意保持较短，避免模拟输出在界面中过长影响观察。
        String summary = chapter.getContent().length() > 80
                ? chapter.getContent().substring(0, 80) + "..."
                : chapter.getContent();

        return new ScriptDocument.Scene(
                "scene_%03d".formatted(chapter.getChapterIndex()),
                chapter.getTitle(),
                List.of(chapter.getChapterIndex()),
                "loc_001",
                chapter.getChapterIndex() == 1 ? "night" : "day",
                List.of(protagonist.id(), antagonist.id()),
                "将第 %d 章的叙事冲突转化为可表演的场面。".formatted(chapter.getChapterIndex()),
                summary,
                List.of(
                        new ScriptDocument.SceneBlock("action", null, "场景从一个明确的动作开始，人物进入冲突中心。"),
                        new ScriptDocument.SceneBlock("dialogue", protagonist.id(), "这件事不能再拖了。"),
                        new ScriptDocument.SceneBlock("dialogue", antagonist.id(), "你确定自己知道真相吗？"),
                        new ScriptDocument.SceneBlock("transition", null, "CUT TO:")
                )
        );
    }
}
