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
import com.novelplayer.config.NovelPlayerProperties;
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
 * Covers scene draft caching, context building, validation, failure recording and parallel context semantics.
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
        generator = generatorWithConcurrency(1);
    }

    @Test
    void reusesCachedSceneDraftWhenInputHashMatches() {
        GenerationJob job = persistedJob(12L);
        NovelProject project = new NovelProject("Rain Night", "chapter source");
        NovelChapter chapter = chapter(project, 1);
        GenerationOptions options = GenerationOptions.defaults();
        StoryBible bible = sampleBible();
        PlannedScene scene = plannedScene("scene_001", List.of(1), "loc_001",
                List.of("char_001", "char_002"));
        ScenePlan scenePlan = new ScenePlan(List.of(scene));
        SceneDraft draft = sceneDraft(scene, "Lin questions the shopkeeper.");
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
    void serialGenerationUsesPreviousGeneratedSummary() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        GenerationJob job = persistedJob(21L);
        NovelProject project = new NovelProject("Rain Night", "chapter source");
        NovelChapter chapterOne = chapter(project, 1);
        NovelChapter chapterTwo = chapter(project, 2);
        GenerationOptions options = new GenerationOptions("web_drama", "suspense", 60, 30,
                "Make the protagonist proactive.");
        StoryBible bible = sampleBible();
        PlannedScene sceneOne = plannedScene("scene_001", List.of(1), "loc_001",
                List.of("char_001", "char_002"), "planned summary one");
        PlannedScene sceneTwo = plannedScene("scene_002", List.of(2), "loc_002",
                List.of("char_001"), "planned summary two");
        ScenePlan scenePlan = new ScenePlan(List.of(sceneOne, sceneTwo));
        SceneDraft draftOne = sceneDraft(sceneOne, "generated summary one");
        SceneDraft draftTwo = sceneDraft(sceneTwo, "generated summary two");
        when(repository.findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                eq(21L), any(String.class), eq(GenerationStatus.SUCCEEDED), any(String.class)))
                .thenReturn(Optional.empty());
        when(aiClient.generateSceneDraft(eq(project), any(SceneDraftContext.class), eq(options)))
                .thenReturn(draftOne, draftTwo);

        List<SceneDraft> results = generator.generate(
                job, project, List.of(chapterOne, chapterTwo), scenePlan, bible, options);

        assertThat(results).containsExactly(draftOne, draftTwo);
        ArgumentCaptor<SceneDraftContext> contextCaptor = ArgumentCaptor.forClass(SceneDraftContext.class);
        verify(aiClient, org.mockito.Mockito.times(2)).generateSceneDraft(
                eq(project), contextCaptor.capture(), eq(options));
        List<SceneDraftContext> contexts = contextCaptor.getAllValues();
        assertThat(contexts.get(0).previousSceneSummary()).isNull();
        assertThat(contexts.get(1).previousSceneSummary()).isEqualTo(draftOne.summary());
        assertThat(contexts.get(1).plannedScene()).isEqualTo(sceneTwo);
    }

    @Test
    void parallelGenerationUsesPreviousPlannedSummaryAndAvoidsFineGrainedStageWrites() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        GenerationJob job = persistedJob(22L);
        NovelProject project = new NovelProject("Rain Night", "chapter source");
        NovelChapter chapterOne = chapter(project, 1);
        NovelChapter chapterTwo = chapter(project, 2);
        GenerationOptions options = GenerationOptions.defaults();
        StoryBible bible = sampleBible();
        PlannedScene sceneOne = plannedScene("scene_001", List.of(1), "loc_001",
                List.of("char_001", "char_002"), "planned summary one");
        PlannedScene sceneTwo = plannedScene("scene_002", List.of(2), "loc_002",
                List.of("char_001"), "planned summary two");
        ScenePlan scenePlan = new ScenePlan(List.of(sceneOne, sceneTwo));
        SceneDraftGenerator parallelGenerator = generatorWithConcurrency(2);
        when(repository.findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                eq(22L), any(String.class), eq(GenerationStatus.SUCCEEDED), any(String.class)))
                .thenReturn(Optional.empty());
        when(aiClient.generateSceneDraft(eq(project), any(SceneDraftContext.class), eq(options)))
                .thenAnswer(invocation -> {
                    SceneDraftContext context = invocation.getArgument(1);
                    return sceneDraft(context.plannedScene(), "generated " + context.plannedScene().id());
                });

        List<SceneDraft> results = parallelGenerator.generate(
                job, project, List.of(chapterOne, chapterTwo), scenePlan, bible, options);

        assertThat(results).extracting(SceneDraft::id).containsExactly("scene_001", "scene_002");
        ArgumentCaptor<SceneDraftContext> contextCaptor = ArgumentCaptor.forClass(SceneDraftContext.class);
        verify(aiClient, org.mockito.Mockito.times(2)).generateSceneDraft(
                eq(project), contextCaptor.capture(), eq(options));
        List<SceneDraftContext> contexts = contextCaptor.getAllValues();
        assertThat(contexts.get(0).previousSceneSummary()).isNull();
        assertThat(contexts.get(1).previousSceneSummary()).isEqualTo(sceneOne.summary());
        verify(lifecycleServiceProvider, never()).getIfAvailable();
    }

    @Test
    void recordsFailedStageWhenSceneReferencesMissingChapter() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        GenerationJob job = persistedJob(31L);
        NovelProject project = new NovelProject("Rain Night", "chapter source");
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
        NovelProject project = new NovelProject("Rain Night", "chapter source");
        NovelChapter chapter = chapter(project, 1);
        GenerationOptions options = GenerationOptions.defaults();
        StoryBible bible = sampleBible();
        PlannedScene scene = plannedScene("scene_001", List.of(1), "loc_001", List.of("char_001"));
        PlannedScene shiftedScene = plannedScene("scene_999", List.of(1), "loc_001", List.of("char_001"));
        ScenePlan scenePlan = new ScenePlan(List.of(scene));
        SceneDraft invalidDraft = sceneDraft(shiftedScene, "The model wrote a different scene.");
        when(repository.findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                eq(41L), eq(GenerationStageNames.sceneDraft("scene_001")), eq(GenerationStatus.SUCCEEDED),
                any(String.class)))
                .thenReturn(Optional.empty());
        when(aiClient.generateSceneDraft(eq(project), any(SceneDraftContext.class), eq(options)))
                .thenReturn(invalidDraft);

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
        NovelProject project = new NovelProject("Rain Night", "chapter source");
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
                "Lin finds a clue.",
                List.of(new DraftSceneBlock("dialogue", "char_999", "I know the truth."))
        );
        when(repository.findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                eq(51L), eq(GenerationStageNames.sceneDraft("scene_001")), eq(GenerationStatus.SUCCEEDED),
                any(String.class)))
                .thenReturn(Optional.empty());
        when(aiClient.generateSceneDraft(eq(project), any(SceneDraftContext.class), eq(options)))
                .thenReturn(invalidDraft);

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
        NovelProject project = new NovelProject("Rain Night", "chapter source");
        PlannedScene scene = plannedScene("scene_001", List.of(1), "loc_001", List.of("char_001"));

        assertThatThrownBy(() -> generator.generate(
                job, project, List.of(), new ScenePlan(List.of(scene)), sampleBible(), GenerationOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chapters");
        verify(repository, never()).findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                any(), any(), any(), any());
    }

    private SceneDraftGenerator generatorWithConcurrency(int concurrency) {
        NovelPlayerProperties properties = new NovelPlayerProperties();
        properties.getGeneration().setSceneDraftConcurrency(concurrency);
        return new SceneDraftGenerator(
                aiClient,
                stageStore,
                lifecycleServiceProvider,
                properties,
                new GenerationStageParallelExecutor(Runnable::run)
        );
    }

    private static NovelChapter chapter(NovelProject project, int chapterIndex) {
        return new NovelChapter(project, chapterIndex, "Chapter " + chapterIndex,
                "Chapter " + chapterIndex + " content. Lin finds a new clue.");
    }

    private static StoryBible sampleBible() {
        return new StoryBible(
                List.of(
                        new BibleCharacter("char_001", "Lin", List.of(), "protagonist",
                                "Find the truth", List.of("observant"), "short sentences"),
                        new BibleCharacter("char_002", "Shopkeeper", List.of(), "supporting",
                                "Hide part of the truth", List.of("guarded"), "steady tone")
                ),
                List.of(
                        new BibleLocation("loc_001", "Bookshop", "interior", "dim and narrow"),
                        new BibleLocation("loc_002", "Archive", "interior", "full of ledgers")
                ),
                "Lin searches for the truth behind a disappearance.",
                List.of("truth", "choice"),
                List.of("Do not reveal the father before chapter seven.")
        );
    }

    private static PlannedScene plannedScene(String id, List<Integer> sourceChapters, String locationId,
                                             List<String> characters) {
        return plannedScene(id, sourceChapters, locationId, characters, "Lin presses for the key clue.");
    }

    private static PlannedScene plannedScene(String id, List<Integer> sourceChapters, String locationId,
                                             List<String> characters, String summary) {
        return new PlannedScene(
                id,
                "Testing the bookshop",
                sourceChapters,
                locationId,
                "night",
                characters,
                "Let the protagonist actively approach the truth",
                summary,
                List.of("Set an investigation goal", "Create resistance", "Leave a hook")
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
                        new DraftSceneBlock("action", null, "Lin enters the room."),
                        new DraftSceneBlock("dialogue", scene.characters().getFirst(), "We cannot wait.")
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
