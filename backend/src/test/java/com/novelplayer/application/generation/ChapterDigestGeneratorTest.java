package com.novelplayer.application.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelplayer.ai.StagedScriptAiClient;
import com.novelplayer.application.generation.model.ChapterDigest;
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
 * 涵盖章节摘要缓存、持久化、失败记录以及可配置的并行执行能力。
 * Covers chapter digest caching, persistence, failure recording and configured parallel execution.
 */
@ExtendWith(MockitoExtension.class)
class ChapterDigestGeneratorTest {

    @Mock
    private StagedScriptAiClient aiClient;

    @Mock
    private GenerationStageResultRepository repository;

    @Mock
    private ObjectProvider<GenerationJobLifecycleService> lifecycleServiceProvider;

    private GenerationStageStore stageStore;
    private ChapterDigestGenerator generator;

    @BeforeEach
    void setUp() {
        stageStore = new GenerationStageStore(repository, new ObjectMapper());
        generator = generatorWithConcurrency(1);
    }

    @Test
    void reusesCachedChapterDigestWhenInputHashMatches() {
        GenerationJob job = persistedJob(12L);
        NovelProject project = new NovelProject("Rain Night", "chapter source");
        NovelChapter chapter = new NovelChapter(project, 1, "Rain Night", "She finds a letter.");
        GenerationOptions options = GenerationOptions.defaults();
        ChapterDigest digest = digest(1, "Rain Night", "She finds a letter.");
        String inputHash = stageStore.sha256OfJson(chapterDigestInput(project, chapter, options));
        GenerationStageResult cached = new GenerationStageResult(
                job,
                GenerationStageNames.chapterDigest(1),
                GenerationStatus.SUCCEEDED,
                inputHash,
                toJson(digest),
                null
        );
        when(repository.findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                12L, GenerationStageNames.chapterDigest(1), GenerationStatus.SUCCEEDED, inputHash))
                .thenReturn(Optional.of(cached));

        List<ChapterDigest> results = generator.generate(job, project, List.of(chapter), options);

        assertThat(results).containsExactly(digest);
        verify(aiClient, never()).generateChapterDigest(any(), any(), any());
    }

    @Test
    void generatesAndPersistsDigestWhenCacheMisses() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        GenerationJob job = persistedJob(21L);
        NovelProject project = new NovelProject("Rain Night", "chapter source");
        NovelChapter chapter = new NovelChapter(project, 1, "Rain Night",
                "She finds a letter and realizes the case is not closed.");
        GenerationOptions options = new GenerationOptions("web_drama", "suspense", 60, 30,
                "Make the protagonist proactive.");
        ChapterDigest digest = digest(1, "Rain Night", "The letter changes the case.");
        when(repository.findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                eq(21L), eq(GenerationStageNames.chapterDigest(1)), eq(GenerationStatus.SUCCEEDED), any(String.class)))
                .thenReturn(Optional.empty());
        when(aiClient.generateChapterDigest(project, chapter, options)).thenReturn(digest);

        List<ChapterDigest> results = generator.generate(job, project, List.of(chapter), options);

        assertThat(results).containsExactly(digest);
        ArgumentCaptor<GenerationStageResult> captor = ArgumentCaptor.forClass(GenerationStageResult.class);
        verify(repository).save(captor.capture());
        GenerationStageResult saved = captor.getValue();
        assertThat(saved.getStageName()).isEqualTo(GenerationStageNames.chapterDigest(1));
        assertThat(saved.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(saved.getOutputJson()).contains("\"summary\":\"The letter changes the case.\"");
    }

    @Test
    void recordsFailedStageAndPropagatesException() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        GenerationJob job = persistedJob(31L);
        NovelProject project = new NovelProject("Rain Night", "chapter source");
        NovelChapter chapter = new NovelChapter(project, 1, "Rain Night", "She finds a letter.");
        GenerationOptions options = GenerationOptions.defaults();
        RuntimeException failure = new RuntimeException("model returned invalid content");
        when(repository.findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                eq(31L), eq(GenerationStageNames.chapterDigest(1)), eq(GenerationStatus.SUCCEEDED), any(String.class)))
                .thenReturn(Optional.empty());
        when(aiClient.generateChapterDigest(project, chapter, options)).thenThrow(failure);

        assertThatThrownBy(() -> generator.generate(job, project, List.of(chapter), options))
                .isSameAs(failure);
        ArgumentCaptor<GenerationStageResult> captor = ArgumentCaptor.forClass(GenerationStageResult.class);
        verify(repository).save(captor.capture());
        GenerationStageResult saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(saved.getStageName()).isEqualTo(GenerationStageNames.chapterDigest(1));
        assertThat(saved.getErrorMessage()).isEqualTo("model returned invalid content");
    }

    @Test
    void rejectsEmptyChapterListEarly() {
        GenerationJob job = persistedJob(41L);
        NovelProject project = new NovelProject("Rain Night", "chapter source");

        assertThatThrownBy(() -> generator.generate(job, project, List.of(), GenerationOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chapters");
    }

    @Test
    void parallelGenerationKeepsChapterOrderAndAvoidsFineGrainedStageWrites() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        GenerationJob job = persistedJob(51L);
        NovelProject project = new NovelProject("Rain Night", "chapter source");
        NovelChapter chapterOne = new NovelChapter(project, 1, "Chapter 1", "First chapter.");
        NovelChapter chapterTwo = new NovelChapter(project, 2, "Chapter 2", "Second chapter.");
        GenerationOptions options = GenerationOptions.defaults();
        ChapterDigestGenerator parallelGenerator = generatorWithConcurrency(2);
        when(repository.findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                eq(51L), any(String.class), eq(GenerationStatus.SUCCEEDED), any(String.class)))
                .thenReturn(Optional.empty());
        when(aiClient.generateChapterDigest(eq(project), any(NovelChapter.class), eq(options)))
                .thenAnswer(invocation -> {
                    NovelChapter chapter = invocation.getArgument(1);
                    return digest(chapter.getChapterIndex(), chapter.getTitle(),
                            "summary-" + chapter.getChapterIndex());
                });

        List<ChapterDigest> results = parallelGenerator.generate(
                job, project, List.of(chapterOne, chapterTwo), options);

        assertThat(results).extracting(ChapterDigest::chapterIndex).containsExactly(1, 2);
        assertThat(results).extracting(ChapterDigest::summary).containsExactly("summary-1", "summary-2");
        verify(lifecycleServiceProvider, never()).getIfAvailable();
    }

    private ChapterDigestGenerator generatorWithConcurrency(int concurrency) {
        NovelPlayerProperties properties = new NovelPlayerProperties();
        properties.getGeneration().setChapterDigestConcurrency(concurrency);
        return new ChapterDigestGenerator(
                aiClient,
                stageStore,
                lifecycleServiceProvider,
                properties,
                new GenerationStageParallelExecutor(Runnable::run)
        );
    }

    private static ChapterDigest digest(int chapterIndex, String title, String summary) {
        return new ChapterDigest(
                chapterIndex,
                title,
                summary,
                List.of("event-" + chapterIndex),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
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

    private static String toJson(ChapterDigest digest) {
        try {
            return new ObjectMapper().writeValueAsString(digest);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ChapterDigestInput chapterDigestInput(NovelProject project, NovelChapter chapter,
                                                         GenerationOptions options) {
        return new ChapterDigestInput(
                project.getId(),
                project.getTitle(),
                chapter.getChapterIndex(),
                chapter.getTitle(),
                chapter.getContent(),
                options.format(),
                options.tone(),
                options.dialogueDensity(),
                options.narrationRetention(),
                options.hasAdditionalInstructions(),
                options.additionalInstructions()
        );
    }

    private record ChapterDigestInput(
            Long projectId,
            String projectTitle,
            int chapterIndex,
            String chapterTitle,
            String chapterContent,
            String format,
            String tone,
            int dialogueDensity,
            int narrationRetention,
            boolean hasAdditionalInstructions,
            String additionalInstructions
    ) {
    }
}
