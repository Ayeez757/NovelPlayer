package com.novelplayer.application.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelplayer.ai.StagedScriptAiClient;
import com.novelplayer.application.generation.model.BibleCharacter;
import com.novelplayer.application.generation.model.BibleLocation;
import com.novelplayer.application.generation.model.DraftSceneBlock;
import com.novelplayer.application.generation.model.PlannedScene;
import com.novelplayer.application.generation.model.SceneDraft;
import com.novelplayer.application.generation.model.SceneDraftContext;
import com.novelplayer.application.generation.model.ScenePlan;
import com.novelplayer.application.generation.model.StoryBible;
import com.novelplayer.domain.generation.GenerationJob;
import com.novelplayer.domain.generation.GenerationStageResult;
import com.novelplayer.domain.generation.GenerationStatus;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;
import com.novelplayer.infra.repository.GenerationStageResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖分场草稿阶段生成器的逐场缓存、最小上下文构造、引用校验和失败记录。
 */
@ExtendWith(MockitoExtension.class)
class SceneDraftGeneratorTest {

    @Mock
    private StagedScriptAiClient aiClient;

    @Mock
    private GenerationStageResultRepository repository;

    @Mock
    private ObjectProvider<GenerationJobLifecycleService> lifecycleServiceProvider;

    private GenerationStageStore stageStore;
    private SceneDraftGenerator generator;

    @BeforeEach
    void setUp() {
        stageStore = new GenerationStageStore(repository, new ObjectMapper());
        /*
         * 旧测试构造器参数只有 2 个：
         * generator = new SceneDraftGenerator(aiClient, stageStore);
         */
        generator = new SceneDraftGenerator(aiClient, stageStore, lifecycleServiceProvider);
    }

    @Test
    void reusesCachedSceneDraftWhenInputHashMatches() {
        GenerationJob job = persistedJob(12L);
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        NovelChapter chapter = chapter(project, 1);
        GenerationOptions options = GenerationOptions.defaults();
        StoryBible bible = sampleBible();
        PlannedScene scene = plannedScene("scene_001", List.of(1), "loc_001", List.of("char_001", "char_002"));
        ScenePlan scenePlan = new ScenePlan(List.of(scene));
        SceneDraft draft = sceneDraft(scene, "林安逼问店主。");
        SceneDraftContext context = new SceneDraftContext(scene, List.of(chapter), bible.characters(),
                bible.locations().getFirst(), bible.continuityRules(), null);
        String inputHash = stageStore.sha256OfJson(sceneDraftInput(project, context, options));
        GenerationStageResult cached = new GenerationStageResult(
                job,
                GenerationStageNames.sceneDraft("scene_001"),
                GenerationStatus.SUCCEEDED,
                inputHash,
                toJson(draft),
                null
        );
        when(repository.findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                12L, GenerationStageNames.sceneDraft("scene_001"), GenerationStatus.SUCCEEDED, inputHash))
                .thenReturn(Optional.of(cached));

        List<SceneDraft> results = generator.generate(job, project, List.of(chapter), scenePlan, bible, options);

        assertThat(results).containsExactly(draft);
        verify(aiClient, never()).generateSceneDraft(any(), any(), any());
    }

    @Test
    void generatesEachSceneWithMinimalContextAndPreviousSummary() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        GenerationJob job = persistedJob(21L);
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。\n第二章 旧账\n她找到旧账本。");
        NovelChapter chapterOne = chapter(project, 1);
        NovelChapter chapterTwo = chapter(project, 2);
        GenerationOptions options = new GenerationOptions("web_drama", "suspense", 60, 30, "强化主角主动性");
        StoryBible bible = sampleBible();
        PlannedScene sceneOne = plannedScene("scene_001", List.of(1), "loc_001", List.of("char_001", "char_002"));
        PlannedScene sceneTwo = plannedScene("scene_002", List.of(2), "loc_002", List.of("char_001"));
        ScenePlan scenePlan = new ScenePlan(List.of(sceneOne, sceneTwo));
        SceneDraft draftOne = sceneDraft(sceneOne, "林安逼问店主，拿到信件线索。");
        SceneDraft draftTwo = sceneDraft(sceneTwo, "林安根据上一场线索找到旧账本。");
        when(repository.findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                eq(21L), any(String.class), eq(GenerationStatus.SUCCEEDED), any(String.class)))
                .thenReturn(Optional.empty());
        when(aiClient.generateSceneDraft(eq(project), any(SceneDraftContext.class), eq(options)))
                .thenReturn(draftOne, draftTwo);

        List<SceneDraft> results = generator.generate(
                job, project, List.of(chapterOne, chapterTwo), scenePlan, bible, options);

        assertThat(results).containsExactly(draftOne, draftTwo);
        ArgumentCaptor<SceneDraftContext> contextCaptor = ArgumentCaptor.forClass(SceneDraftContext.class);
        verify(aiClient, org.mockito.Mockito.times(2)).generateSceneDraft(eq(project), contextCaptor.capture(), eq(options));
        List<SceneDraftContext> contexts = contextCaptor.getAllValues();
        assertThat(contexts.get(0).plannedScene()).isEqualTo(sceneOne);
        assertThat(contexts.get(0).sourceChapters()).extracting(NovelChapter::getChapterIndex).containsExactly(1);
        assertThat(contexts.get(0).characters()).extracting(BibleCharacter::id).containsExactly("char_001", "char_002");
        assertThat(contexts.get(0).location().id()).isEqualTo("loc_001");
        assertThat(contexts.get(0).previousSceneSummary()).isNull();
        assertThat(contexts.get(1).plannedScene()).isEqualTo(sceneTwo);
        assertThat(contexts.get(1).sourceChapters()).extracting(NovelChapter::getChapterIndex).containsExactly(2);
        assertThat(contexts.get(1).characters()).extracting(BibleCharacter::id).containsExactly("char_001");
        assertThat(contexts.get(1).location().id()).isEqualTo("loc_002");
        assertThat(contexts.get(1).previousSceneSummary()).isEqualTo(draftOne.summary());

        ArgumentCaptor<GenerationStageResult> resultCaptor = ArgumentCaptor.forClass(GenerationStageResult.class);
        verify(repository, org.mockito.Mockito.times(2)).save(resultCaptor.capture());
        assertThat(resultCaptor.getAllValues()).extracting(GenerationStageResult::getStageName)
                .containsExactly(GenerationStageNames.sceneDraft("scene_001"), GenerationStageNames.sceneDraft("scene_002"));
        assertThat(resultCaptor.getAllValues()).extracting(GenerationStageResult::getStatus)
                .containsExactly(GenerationStatus.SUCCEEDED, GenerationStatus.SUCCEEDED);
    }

    @Test
    void recordsFailedStageWhenSceneReferencesMissingChapter() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        GenerationJob job = persistedJob(31L);
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        NovelChapter chapter = chapter(project, 1);
        StoryBible bible = sampleBible();
        PlannedScene scene = plannedScene("scene_001", List.of(2), "loc_001", List.of("char_001"));
        ScenePlan scenePlan = new ScenePlan(List.of(scene));

        assertThatThrownBy(() -> generator.generate(
                job, project, List.of(chapter), scenePlan, bible, GenerationOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source chapter");
        ArgumentCaptor<GenerationStageResult> captor = ArgumentCaptor.forClass(GenerationStageResult.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStageName()).isEqualTo(GenerationStageNames.sceneDraft("scene_001"));
        assertThat(captor.getValue().getStatus()).isEqualTo(GenerationStatus.FAILED);
    }

    @Test
    void recordsFailedStageWhenDraftDoesNotMatchPlannedScene() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        GenerationJob job = persistedJob(41L);
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        NovelChapter chapter = chapter(project, 1);
        GenerationOptions options = GenerationOptions.defaults();
        StoryBible bible = sampleBible();
        PlannedScene scene = plannedScene("scene_001", List.of(1), "loc_001", List.of("char_001"));
        PlannedScene shiftedScene = plannedScene("scene_999", List.of(1), "loc_001", List.of("char_001"));
        ScenePlan scenePlan = new ScenePlan(List.of(scene));
        SceneDraft invalidDraft = sceneDraft(shiftedScene, "模型写偏到了别的场景。");
        when(repository.findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                eq(41L), eq(GenerationStageNames.sceneDraft("scene_001")), eq(GenerationStatus.SUCCEEDED), any(String.class)))
                .thenReturn(Optional.empty());
        when(aiClient.generateSceneDraft(eq(project), any(SceneDraftContext.class), eq(options))).thenReturn(invalidDraft);

        assertThatThrownBy(() -> generator.generate(job, project, List.of(chapter), scenePlan, bible, options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("planned scene");
        ArgumentCaptor<GenerationStageResult> captor = ArgumentCaptor.forClass(GenerationStageResult.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(GenerationStatus.FAILED);
    }

    @Test
    void recordsFailedStageWhenDraftBlockSpeakerIsUnknown() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        GenerationJob job = persistedJob(51L);
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        NovelChapter chapter = chapter(project, 1);
        GenerationOptions options = GenerationOptions.defaults();
        StoryBible bible = sampleBible();
        PlannedScene scene = plannedScene("scene_001", List.of(1), "loc_001", List.of("char_001"));
        ScenePlan scenePlan = new ScenePlan(List.of(scene));
        SceneDraft invalidDraft = new SceneDraft(
                scene.id(),
                scene.title(),
                scene.sourceChapters(),
                scene.locationId(),
                scene.timeOfDay(),
                scene.characters(),
                scene.dramaticPurpose(),
                "林安发现线索。",
                List.of(new DraftSceneBlock("dialogue", "char_999", "我知道真相。"))
        );
        when(repository.findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                eq(51L), eq(GenerationStageNames.sceneDraft("scene_001")), eq(GenerationStatus.SUCCEEDED), any(String.class)))
                .thenReturn(Optional.empty());
        when(aiClient.generateSceneDraft(eq(project), any(SceneDraftContext.class), eq(options))).thenReturn(invalidDraft);

        assertThatThrownBy(() -> generator.generate(job, project, List.of(chapter), scenePlan, bible, options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("char_999");
        ArgumentCaptor<GenerationStageResult> captor = ArgumentCaptor.forClass(GenerationStageResult.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(GenerationStatus.FAILED);
    }

    @Test
    void rejectsEmptyChapterListBeforeStageProcessing() {
        GenerationJob job = persistedJob(61L);
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        PlannedScene scene = plannedScene("scene_001", List.of(1), "loc_001", List.of("char_001"));

        assertThatThrownBy(() -> generator.generate(
                job, project, List.of(), new ScenePlan(List.of(scene)), sampleBible(), GenerationOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chapters");
        verify(repository, never()).findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                any(), any(), any(), any());
    }

    private static NovelChapter chapter(NovelProject project, int chapterIndex) {
        return new NovelChapter(project, chapterIndex, "第%d章".formatted(chapterIndex),
                "第%d章正文，林安发现新的线索。".formatted(chapterIndex));
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

    private static String toJson(SceneDraft draft) {
        try {
            return new ObjectMapper().writeValueAsString(draft);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static SceneDraftInput sceneDraftInput(NovelProject project, SceneDraftContext context,
                                                   GenerationOptions options) {
        return new SceneDraftInput(
                project.getId(),
                project.getTitle(),
                context.plannedScene(),
                context.sourceChapters().stream()
                        .map(SourceChapterInput::from)
                        .toList(),
                context.characters(),
                context.location(),
                context.continuityRules(),
                context.previousSceneSummary(),
                options.format(),
                options.tone(),
                options.dialogueDensity(),
                options.narrationRetention(),
                options.hasAdditionalInstructions(),
                options.additionalInstructions()
        );
    }

    private record SceneDraftInput(
            Long projectId,
            String projectTitle,
            PlannedScene plannedScene,
            List<SourceChapterInput> sourceChapters,
            List<BibleCharacter> characters,
            BibleLocation location,
            List<String> continuityRules,
            String previousSceneSummary,
            String format,
            String tone,
            int dialogueDensity,
            int narrationRetention,
            boolean hasAdditionalInstructions,
            String additionalInstructions
    ) {
    }

    private record SourceChapterInput(
            Long chapterId,
            int chapterIndex,
            String title,
            String content
    ) {
        private static SourceChapterInput from(NovelChapter chapter) {
            return new SourceChapterInput(
                    chapter.getId(),
                    chapter.getChapterIndex(),
                    chapter.getTitle(),
                    chapter.getContent()
            );
        }
    }
}
