package com.novelplayer.application.generation;

import com.novelplayer.ai.StagedScriptAiClient;
import com.novelplayer.application.generation.model.ChapterDigest;
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
 * 覆盖章节摘要阶段生成器的缓存复用、正常生成和失败记录行为。
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

    /**
     * 为每个用例创建阶段存储和章节摘要生成器。
     */
    @BeforeEach
    void setUp() {
        stageStore = new GenerationStageStore(repository, new com.fasterxml.jackson.databind.ObjectMapper());
        /*
         * 旧测试构造器参数只有 2 个：
         * generator = new ChapterDigestGenerator(aiClient, stageStore);
         */
        generator = new ChapterDigestGenerator(aiClient, stageStore, lifecycleServiceProvider);
    }

    /**
     * 验证输入哈希命中时会复用已保存的章节摘要。
     */
    @Test
    void reusesCachedChapterDigestWhenInputHashMatches() {
        GenerationJob job = persistedJob(12L);
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        NovelChapter chapter = new NovelChapter(project, 1, "雨夜", "她发现一封信。");
        GenerationOptions options = GenerationOptions.defaults();
        ChapterDigest digest = new ChapterDigest(
                1,
                "雨夜",
                "她发现一封信。",
                List.of("发现信件"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
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

    /**
     * 验证缓存未命中时会调用 AI 并保存成功阶段结果。
     */
    @Test
    void generatesAndPersistsDigestWhenCacheMisses() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        GenerationJob job = persistedJob(21L);
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        NovelChapter chapter = new NovelChapter(project, 1, "雨夜", "她在旧书店发现一封信，并意识到父亲失踪另有隐情。");
        GenerationOptions options = new GenerationOptions("web_drama", "suspense", 60, 30, "强化主角主动性");
        ChapterDigest digest = new ChapterDigest(
                1,
                "雨夜",
                "她在旧书店发现一封信。",
                List.of("发现信件"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
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
        assertThat(saved.getOutputJson()).contains("\"summary\":\"她在旧书店发现一封信。\"");
    }

    /**
     * 验证 AI 生成失败时会记录失败阶段并继续抛出原异常。
     */
    @Test
    void recordsFailedStageAndPropagatesException() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        GenerationJob job = persistedJob(31L);
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        NovelChapter chapter = new NovelChapter(project, 1, "雨夜", "她在旧书店发现一封信，并意识到父亲失踪另有隐情。");
        GenerationOptions options = GenerationOptions.defaults();
        RuntimeException failure = new RuntimeException("模型返回非法内容");
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
        assertThat(saved.getErrorMessage()).isEqualTo("模型返回非法内容");
    }

    /**
     * 验证空章节列表会在进入阶段查询前被拒绝。
     */
    @Test
    void rejectsEmptyChapterListEarly() {
        GenerationJob job = persistedJob(41L);
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");

        assertThatThrownBy(() -> generator.generate(job, project, List.of(), GenerationOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chapters");
    }

    /**
     * 构造带主键的生成任务，模拟已持久化状态。
     *
     * @param id 任务主键。
     * @return 已设置主键的生成任务。
     */
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

    /**
     * 将章节摘要转为 JSON，便于模拟缓存命中的阶段输出。
     *
     * @param digest 章节摘要。
     * @return JSON 文本。
     */
    private static String toJson(ChapterDigest digest) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(digest);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * 构造与生产代码一致的章节摘要输入快照，用于计算预期缓存哈希。
     *
     * @param project 小说改编项目。
     * @param chapter 待生成摘要的章节。
     * @param options 生成参数。
     * @return 哈希输入快照。
     */
    private static ChapterDigestInput chapterDigestInput(NovelProject project, NovelChapter chapter, GenerationOptions options) {
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

    /**
     * 章节摘要测试使用的输入快照结构。
     *
     * @param projectId 项目主键。
     * @param projectTitle 项目标题。
     * @param chapterIndex 章节序号。
     * @param chapterTitle 章节标题。
     * @param chapterContent 章节正文。
     * @param format 剧本形式。
     * @param tone 整体风格。
     * @param dialogueDensity 对白密度。
     * @param narrationRetention 旁白保留度。
     * @param hasAdditionalInstructions 是否包含补充要求。
     * @param additionalInstructions 补充要求。
     */
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
