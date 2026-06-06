package com.novelplayer.application.generation;

import com.novelplayer.application.generation.model.ChapterDigest;
import com.novelplayer.application.generation.model.DraftSceneBlock;
import com.novelplayer.application.generation.model.PlannedScene;
import com.novelplayer.application.generation.model.SceneDraft;
import com.novelplayer.application.generation.model.ScenePlan;
import com.novelplayer.application.generation.model.StoryBible;
import com.novelplayer.application.script.ScriptSchemaValidator;
import com.novelplayer.domain.generation.GenerationJob;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;
import com.novelplayer.domain.script.ScriptDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖阶段化剧本生成管线的高层编排顺序和最终校验调用。
 */
@ExtendWith(MockitoExtension.class)
class ScriptGenerationPipelineTest {

    @Mock
    private ChapterDigestGenerator chapterDigestGenerator;

    @Mock
    private StoryBibleGenerator storyBibleGenerator;

    @Mock
    private ScenePlanner scenePlanner;

    @Mock
    private SceneDraftGenerator sceneDraftGenerator;

    @Mock
    private ScriptAssembler scriptAssembler;

    @Mock
    private ScriptSchemaValidator validator;

    @Test
    void orchestratesStagedGenerationAndValidatesFinalDocument() {
        ScriptGenerationPipeline pipeline = new ScriptGenerationPipeline(
                chapterDigestGenerator,
                storyBibleGenerator,
                scenePlanner,
                sceneDraftGenerator,
                scriptAssembler,
                validator
        );
        GenerationJob job = persistedJob(99L);
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        NovelChapter chapter = new NovelChapter(project, 1, "雨夜", "她发现一封信。");
        GenerationOptions options = GenerationOptions.defaults();
        List<NovelChapter> chapters = List.of(chapter);
        List<ChapterDigest> digests = List.of(sampleDigest());
        StoryBible bible = sampleBible();
        PlannedScene plannedScene = samplePlannedScene();
        ScenePlan scenePlan = new ScenePlan(List.of(plannedScene));
        List<SceneDraft> drafts = List.of(sampleDraft(plannedScene));
        ScriptDocument document = sampleDocument(plannedScene);

        when(chapterDigestGenerator.generate(job, project, chapters, options)).thenReturn(digests);
        when(storyBibleGenerator.generate(job, project, digests, options)).thenReturn(bible);
        when(scenePlanner.plan(job, project, digests, bible, options)).thenReturn(scenePlan);
        when(sceneDraftGenerator.generate(job, project, chapters, scenePlan, bible, options)).thenReturn(drafts);
        when(scriptAssembler.assembleDrafts(project, options, bible, scenePlan, drafts)).thenReturn(document);

        ScriptDocument result = pipeline.generate(job, project, chapters, options);

        assertThat(result).isSameAs(document);
        assertThat(job.getCurrentStage()).isEqualTo(GenerationStageNames.SCRIPT_ASSEMBLY);
        InOrder inOrder = inOrder(chapterDigestGenerator, storyBibleGenerator, scenePlanner,
                sceneDraftGenerator, scriptAssembler, validator);
        inOrder.verify(chapterDigestGenerator).generate(job, project, chapters, options);
        inOrder.verify(storyBibleGenerator).generate(job, project, digests, options);
        inOrder.verify(scenePlanner).plan(job, project, digests, bible, options);
        inOrder.verify(sceneDraftGenerator).generate(job, project, chapters, scenePlan, bible, options);
        inOrder.verify(scriptAssembler).assembleDrafts(project, options, bible, scenePlan, drafts);
        inOrder.verify(validator).validate(document);
    }

    @Test
    void rejectsEmptyChapterListBeforeAnyStageRuns() {
        ScriptGenerationPipeline pipeline = new ScriptGenerationPipeline(
                chapterDigestGenerator,
                storyBibleGenerator,
                scenePlanner,
                sceneDraftGenerator,
                scriptAssembler,
                validator
        );
        GenerationJob job = persistedJob(101L);
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");

        assertThatThrownBy(() -> pipeline.generate(job, project, List.of(), GenerationOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chapters");
        verify(chapterDigestGenerator, org.mockito.Mockito.never())
                .generate(org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }

    private static ChapterDigest sampleDigest() {
        return new ChapterDigest(
                1,
                "雨夜",
                "林安在旧书店发现一封信。",
                List.of("发现信件"),
                List.of(),
                List.of(),
                List.of("店主回避问题"),
                List.of("父亲为何失踪"),
                List.of("把信件作为场景高潮")
        );
    }

    private static StoryBible sampleBible() {
        return new StoryBible(
                List.of(new com.novelplayer.application.generation.model.BibleCharacter(
                        "char_001", "林安", List.of("她"), "protagonist",
                        "寻找父亲失踪真相", List.of("敏感"), "短句为主"
                )),
                List.of(new com.novelplayer.application.generation.model.BibleLocation(
                        "loc_001", "旧书店", "interior", "昏暗"
                )),
                "林安寻找父亲失踪真相。",
                List.of("真相"),
                List.of("第七章前不能揭露父亲身份")
        );
    }

    private static PlannedScene samplePlannedScene() {
        return new PlannedScene(
                "scene_001",
                "旧书店试探",
                List.of(1),
                "loc_001",
                "night",
                List.of("char_001"),
                "让主角第一次主动逼近真相",
                "林安追问关键线索。",
                List.of("建立调查目标")
        );
    }

    private static SceneDraft sampleDraft(PlannedScene scene) {
        return new SceneDraft(
                scene.id(),
                scene.title(),
                scene.sourceChapters(),
                scene.locationId(),
                scene.timeOfDay(),
                scene.characters(),
                scene.dramaticPurpose(),
                scene.summary(),
                List.of(new DraftSceneBlock("action", null, "林安推门进入。"))
        );
    }

    private static ScriptDocument sampleDocument(PlannedScene scene) {
        return new ScriptDocument(
                "1.0",
                new ScriptDocument.ScriptMetadata("雨夜", "zh-CN", 3, java.time.OffsetDateTime.now()),
                new ScriptDocument.Adaptation("web_drama", "suspense", "林安寻找真相。", List.of("真相")),
                List.of(new ScriptDocument.CharacterProfile(
                        "char_001", "林安", List.of("她"), "protagonist", "寻找真相", List.of("敏感"), "短句"
                )),
                List.of(new ScriptDocument.LocationProfile("loc_001", "旧书店", "interior", "昏暗")),
                List.of(new ScriptDocument.Scene(
                        scene.id(),
                        scene.title(),
                        scene.sourceChapters(),
                        scene.locationId(),
                        scene.timeOfDay(),
                        scene.characters(),
                        scene.dramaticPurpose(),
                        scene.summary(),
                        List.of(new ScriptDocument.SceneBlock("action", null, "林安推门进入。"))
                )),
                List.of()
        );
    }

    private static GenerationJob persistedJob(Long id) {
        GenerationJob job = new GenerationJob(new NovelProject("title", "source"));
        try {
            Field field = GenerationJob.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(job, id);
            return job;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to set job id for test", exception);
        }
    }
}
