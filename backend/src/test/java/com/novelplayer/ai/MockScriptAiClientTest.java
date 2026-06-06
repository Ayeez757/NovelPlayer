package com.novelplayer.ai;

import com.novelplayer.application.generation.GenerationOptions;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;
import com.novelplayer.domain.script.ScriptDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖本地模拟生成对用户补充要求的可见反馈。
 */
class MockScriptAiClientTest {

    @Test
    void echoesAdditionalInstructionsInRevisionNotes() {
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        List<NovelChapter> chapters = List.of(new NovelChapter(project, 1, "雨夜", "她发现一封信。"));
        GenerationOptions options = new GenerationOptions("web_drama", "suspense", 60, 30,
                "强化主角主动性");

        ScriptDocument document = new MockScriptAiClient().generateScript(project, chapters, options);

        assertThat(document.revisionNotes())
                .contains("已接收本次补充改编要求：强化主角主动性");
    }
}
