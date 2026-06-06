package com.novelplayer.application.generation;

import com.novelplayer.application.generation.model.BibleCharacter;
import com.novelplayer.application.generation.model.BibleLocation;
import com.novelplayer.application.generation.model.DraftSceneBlock;
import com.novelplayer.application.generation.model.PlannedScene;
import com.novelplayer.application.generation.model.SceneDraft;
import com.novelplayer.application.generation.model.ScenePlan;
import com.novelplayer.application.generation.model.StoryBible;
import com.novelplayer.domain.project.NovelProject;
import com.novelplayer.domain.script.ScriptDocument;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 覆盖最终剧本文档组装器的确定性映射、元信息构造和一致性校验。
 */
class ScriptAssemblerTest {

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-06-07T01:00:00Z"), ZoneOffset.UTC);
    private final ScriptAssembler assembler = new ScriptAssembler(fixedClock);

    @Test
    void assemblesScriptDocumentFromSceneDraftsDeterministically() {
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        GenerationOptions options = new GenerationOptions("web_drama", "suspense", 60, 30, "强化主角主动性");
        StoryBible bible = sampleBible();
        PlannedScene sceneOne = plannedScene("scene_001", List.of(1), "loc_001", List.of("char_001", "char_002"));
        PlannedScene sceneTwo = plannedScene("scene_002", List.of(2, 3), "loc_002", List.of("char_001"));
        ScenePlan scenePlan = new ScenePlan(List.of(sceneOne, sceneTwo));
        List<SceneDraft> drafts = List.of(
                sceneDraft(sceneOne, "林安逼问店主，拿到信件线索。"),
                sceneDraft(sceneTwo, "林安进入档案室，发现旧账本。")
        );

        ScriptDocument document = assembler.assembleDrafts(project, options, bible, scenePlan, drafts);

        assertThat(document.schemaVersion()).isEqualTo("1.0");
        assertThat(document.metadata().title()).isEqualTo("雨夜");
        assertThat(document.metadata().language()).isEqualTo("zh-CN");
        assertThat(document.metadata().sourceChapterCount()).isEqualTo(3);
        assertThat(document.metadata().generatedAt().toInstant()).isEqualTo(Instant.parse("2026-06-07T01:00:00Z"));
        assertThat(document.adaptation().format()).isEqualTo("web_drama");
        assertThat(document.adaptation().tone()).isEqualTo("suspense");
        assertThat(document.adaptation().logline()).isEqualTo(bible.mainPlot());
        assertThat(document.adaptation().themes()).containsExactly("真相", "选择");
        assertThat(document.characters()).extracting(ScriptDocument.CharacterProfile::id)
                .containsExactly("char_001", "char_002");
        assertThat(document.locations()).extracting(ScriptDocument.LocationProfile::id)
                .containsExactly("loc_001", "loc_002");
        assertThat(document.scenes()).extracting(ScriptDocument.Scene::id)
                .containsExactly("scene_001", "scene_002");
        assertThat(document.scenes().getFirst().blocks()).extracting(ScriptDocument.SceneBlock::type)
                .containsExactly("action", "dialogue");
        assertThat(document.revisionNotes()).anyMatch(note -> note.contains("组装阶段未调用 AI"));
        assertThat(document.revisionNotes()).anyMatch(note -> note.contains("连续性规则"));
    }

    @Test
    void assemblesDocumentFromAlreadyMappedScenes() {
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        StoryBible bible = sampleBible();
        PlannedScene plannedScene = plannedScene("scene_001", List.of(1, 2, 3), "loc_001", List.of("char_001"));
        ScenePlan scenePlan = new ScenePlan(List.of(plannedScene));
        ScriptDocument.Scene scene = new ScriptDocument.Scene(
                plannedScene.id(),
                plannedScene.title(),
                plannedScene.sourceChapters(),
                plannedScene.locationId(),
                plannedScene.timeOfDay(),
                plannedScene.characters(),
                plannedScene.dramaticPurpose(),
                plannedScene.summary(),
                List.of(new ScriptDocument.SceneBlock("action", null, "林安推门进入。"))
        );

        ScriptDocument document = assembler.assemble(
                project, GenerationOptions.defaults(), bible, scenePlan, List.of(scene));

        assertThat(document.scenes()).containsExactly(scene);
        assertThat(document.metadata().sourceChapterCount()).isEqualTo(3);
    }

    @Test
    void rejectsSceneCountMismatch() {
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        PlannedScene plannedScene = plannedScene("scene_001", List.of(1), "loc_001", List.of("char_001"));

        assertThatThrownBy(() -> assembler.assembleDrafts(
                project,
                GenerationOptions.defaults(),
                sampleBible(),
                new ScenePlan(List.of(plannedScene)),
                List.of(sceneDraft(plannedScene, "第一场。"), sceneDraft(plannedScene, "重复场景。"))
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scenePlan size");
    }

    @Test
    void rejectsSceneThatDoesNotMatchPlan() {
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        PlannedScene plannedScene = plannedScene("scene_001", List.of(1), "loc_001", List.of("char_001"));
        PlannedScene shiftedScene = plannedScene("scene_999", List.of(1), "loc_001", List.of("char_001"));

        assertThatThrownBy(() -> assembler.assembleDrafts(
                project,
                GenerationOptions.defaults(),
                sampleBible(),
                new ScenePlan(List.of(plannedScene)),
                List.of(sceneDraft(shiftedScene, "模型写偏到了别的场。"))
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scene id");
    }

    @Test
    void rejectsUnknownDialogueSpeaker() {
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        PlannedScene plannedScene = plannedScene("scene_001", List.of(1), "loc_001", List.of("char_001"));
        ScriptDocument.Scene scene = new ScriptDocument.Scene(
                plannedScene.id(),
                plannedScene.title(),
                plannedScene.sourceChapters(),
                plannedScene.locationId(),
                plannedScene.timeOfDay(),
                plannedScene.characters(),
                plannedScene.dramaticPurpose(),
                plannedScene.summary(),
                List.of(new ScriptDocument.SceneBlock("dialogue", "char_999", "我知道真相。"))
        );

        assertThatThrownBy(() -> assembler.assemble(
                project,
                GenerationOptions.defaults(),
                sampleBible(),
                new ScenePlan(List.of(plannedScene)),
                List.of(scene)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("speaker");
    }

    private static StoryBible sampleBible() {
        return new StoryBible(
                List.of(
                        new BibleCharacter("char_001", "林安", List.of("她"), "protagonist",
                                "寻找父亲失踪真相", List.of("敏感", "克制"), "短句为主"),
                        new BibleCharacter("char_002", "店主", List.of(), "supporting",
                                "隐藏部分真相", List.of("谨慎"), "语气平稳")
                ),
                List.of(
                        new BibleLocation("loc_001", "旧书店", "interior", "昏暗狭窄"),
                        new BibleLocation("loc_002", "档案室", "interior", "堆满旧账本")
                ),
                "林安寻找父亲失踪真相。",
                List.of("真相", "选择"),
                List.of("第七章前不能揭露父亲身份")
        );
    }

    private static PlannedScene plannedScene(String id, List<Integer> sourceChapters, String locationId,
                                             List<String> characters) {
        return new PlannedScene(
                id,
                "旧书店试探",
                sourceChapters,
                locationId,
                "night",
                characters,
                "让主角第一次主动逼近真相",
                "林安追问关键线索。",
                List.of("建立调查目标", "制造人物阻力", "留下父亲失踪悬念")
        );
    }

    private static SceneDraft sceneDraft(PlannedScene scene, String summary) {
        return new SceneDraft(
                scene.id(),
                scene.title(),
                scene.sourceChapters(),
                scene.locationId(),
                scene.timeOfDay(),
                scene.characters(),
                scene.dramaticPurpose(),
                summary,
                List.of(
                        new DraftSceneBlock("action", null, "林安推门进入。"),
                        new DraftSceneBlock("dialogue", scene.characters().getFirst(), "这件事不能再拖了。")
                )
        );
    }
}
