package com.novelplayer.ai;

import com.novelplayer.application.generation.GenerationOptions;
import com.novelplayer.application.generation.model.ChapterDigest;
import com.novelplayer.application.generation.model.SceneDraft;
import com.novelplayer.application.generation.model.SceneDraftContext;
import com.novelplayer.application.generation.model.ScenePlan;
import com.novelplayer.application.generation.model.StoryBible;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 覆盖阶段化 Mock AI 客户端，确保多阶段生成链路可以在本地稳定验证。
 */
class MockStagedScriptAiClientTest {

    private final MockStagedScriptAiClient client = new MockStagedScriptAiClient();

    /**
     * 验证阶段化 Mock 客户端能稳定产出各阶段中间模型。
     */
    @Test
    void generatesStableIntermediateOutputsAcrossStages() {
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        NovelChapter chapter = new NovelChapter(project, 1, "雨夜", "她在旧书店发现一封信，并意识到父亲失踪另有隐情。");
        GenerationOptions options = new GenerationOptions("web_drama", "suspense", 60, 30, "强化主角主动性");

        ChapterDigest digest = client.generateChapterDigest(project, chapter, options);
        StoryBible storyBible = client.generateStoryBible(project, List.of(digest), options);
        ScenePlan scenePlan = client.generateScenePlan(project, List.of(digest), storyBible, options);
        SceneDraftContext context = new SceneDraftContext(
                scenePlan.scenes().getFirst(),
                List.of(chapter),
                storyBible.characters(),
                storyBible.locations().getFirst(),
                storyBible.continuityRules(),
                null
        );
        SceneDraft sceneDraft = client.generateSceneDraft(project, context, options);

        assertThat(digest.chapterIndex()).isEqualTo(1);
        assertThat(digest.summary()).contains("旧书店");
        assertThat(digest.characters()).hasSize(2);
        assertThat(storyBible.characters()).extracting("id").containsExactly("char_001", "char_002");
        assertThat(storyBible.locations()).extracting("id").containsExactly("loc_001");
        assertThat(scenePlan.scenes()).singleElement()
                .satisfies(scene -> {
                    assertThat(scene.id()).isEqualTo("scene_001");
                    assertThat(scene.sourceChapters()).containsExactly(1);
                    assertThat(scene.locationId()).isEqualTo("loc_001");
                    assertThat(scene.characters()).containsExactly("char_001", "char_002");
                });
        assertThat(sceneDraft.id()).isEqualTo("scene_001");
        assertThat(sceneDraft.blocks()).extracting("type")
                .containsExactly("action", "dialogue", "dialogue", "transition");
        assertThat(sceneDraft.blocks()).filteredOn(block -> "dialogue".equals(block.type()))
                .extracting("speakerId")
                .containsExactly("char_001", "char_002");
    }

    /**
     * 验证阶段化 Mock 客户端会拒绝空的阶段输入。
     */
    @Test
    void rejectsEmptyStageInputs() {
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        NovelChapter chapter = new NovelChapter(project, 1, "雨夜", "她发现一封信。");
        GenerationOptions options = GenerationOptions.defaults();
        ChapterDigest digest = client.generateChapterDigest(project, chapter, options);
        StoryBible storyBible = client.generateStoryBible(project, List.of(digest), options);

        assertThatThrownBy(() -> client.generateStoryBible(project, List.of(), options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chapterDigests");
        assertThatThrownBy(() -> client.generateScenePlan(project, List.of(), storyBible, options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chapterDigests");
        assertThatThrownBy(() -> client.generateSceneDraft(project,
                new SceneDraftContext(
                        client.generateScenePlan(project, List.of(digest), storyBible, options).scenes().getFirst(),
                        List.of(),
                        storyBible.characters(),
                        storyBible.locations().getFirst(),
                        storyBible.continuityRules(),
                        null
                ),
                options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceChapters");
    }
}
