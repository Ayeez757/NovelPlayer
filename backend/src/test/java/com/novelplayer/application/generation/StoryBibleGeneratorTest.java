package com.novelplayer.application.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelplayer.ai.LlmJsonClient;
import com.novelplayer.application.generation.model.BibleCharacter;
import com.novelplayer.application.generation.model.BibleLocation;
import com.novelplayer.application.generation.model.ChapterDigest;
import com.novelplayer.application.generation.model.StoryBible;
import com.novelplayer.application.generation.prompt.StoryBiblePromptBuilder;
import com.novelplayer.domain.generation.GenerationJob;
import com.novelplayer.domain.generation.GenerationStageResult;
import com.novelplayer.domain.generation.GenerationStatus;
import com.novelplayer.domain.project.NovelProject;
import com.novelplayer.infra.repository.GenerationStageResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
 * 覆盖故事圣经阶段生成器的缓存复用、成功保存和稳定 ID 校验。
 */
@ExtendWith(MockitoExtension.class)
class StoryBibleGeneratorTest {

    @Mock
    private LlmJsonClient llmJsonClient;

    @Mock
    private GenerationStageResultRepository repository;

    private GenerationStageStore stageStore;
    private StoryBibleGenerator generator;

    /**
     * 为每个用例创建阶段存储和故事圣经生成器。
     */
    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        stageStore = new GenerationStageStore(repository, objectMapper);
        generator = new StoryBibleGenerator(
                llmJsonClient,
                objectMapper,
                stageStore,
                new StoryBiblePromptBuilder("SYSTEM", "INPUT:\n%s")
        );
    }

    /**
     * 验证输入哈希命中时会复用已保存的故事圣经。
     */
    @Test
    void reusesCachedStoryBibleWhenInputHashMatches() {
        GenerationJob job = persistedJob(12L);
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        GenerationOptions options = GenerationOptions.defaults();
        List<ChapterDigest> digests = List.of(sampleDigest());
        StoryBible bible = sampleBible();
        String inputHash = stageStore.sha256OfJson(storyBibleInput(project, digests, options));
        GenerationStageResult cached = new GenerationStageResult(
                job,
                GenerationStageNames.STORY_BIBLE,
                GenerationStatus.SUCCEEDED,
                inputHash,
                toJson(bible),
                null
        );
        when(repository.findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                12L, GenerationStageNames.STORY_BIBLE, GenerationStatus.SUCCEEDED, inputHash))
                .thenReturn(Optional.of(cached));

        StoryBible result = generator.generate(job, project, digests, options);

        assertThat(result).isEqualTo(bible);
        verify(llmJsonClient, never()).requestJson(any(), any(), any());
    }

    /**
     * 验证缓存未命中时会生成、校验并保存故事圣经。
     */
    @Test
    void generatesValidStoryBibleAndPersistsItWhenCacheMisses() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        GenerationJob job = persistedJob(21L);
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        GenerationOptions options = new GenerationOptions("web_drama", "suspense", 60, 30, "强化主角主动性");
        List<ChapterDigest> digests = List.of(sampleDigest());
        StoryBible bible = sampleBible();
        when(repository.findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                eq(21L), eq(GenerationStageNames.STORY_BIBLE), eq(GenerationStatus.SUCCEEDED), any(String.class)))
                .thenReturn(Optional.empty());
        when(llmJsonClient.requestJson(
                eq(GenerationStageNames.STORY_BIBLE),
                eq("SYSTEM"),
                any(String.class)))
                .thenReturn(toJson(bible));

        StoryBible result = generator.generate(job, project, digests, options);

        assertThat(result).isEqualTo(bible);
        ArgumentCaptor<GenerationStageResult> captor = ArgumentCaptor.forClass(GenerationStageResult.class);
        verify(repository).save(captor.capture());
        GenerationStageResult saved = captor.getValue();
        assertThat(saved.getStageName()).isEqualTo(GenerationStageNames.STORY_BIBLE);
        assertThat(saved.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(saved.getOutputJson()).contains("\"id\":\"char_001\"");
        assertThat(saved.getOutputJson()).contains("\"id\":\"loc_001\"");
    }

    /**
     * 验证人物 ID 不符合稳定格式时会记录失败阶段。
     */
    @Test
    void recordsFailedStageWhenCharacterIdIsInvalid() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        GenerationJob job = persistedJob(31L);
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        GenerationOptions options = GenerationOptions.defaults();
        List<ChapterDigest> digests = List.of(sampleDigest());
        StoryBible invalidBible = new StoryBible(
                List.of(new BibleCharacter("hero_001", "主角", List.of(), "protagonist", "寻找真相", List.of(), "短句")),
                List.of(new BibleLocation("loc_001", "旧书店", "interior", "昏暗")),
                "主角寻找真相。",
                List.of("真相"),
                List.of()
        );
        when(repository.findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                eq(31L), eq(GenerationStageNames.STORY_BIBLE), eq(GenerationStatus.SUCCEEDED), any(String.class)))
                .thenReturn(Optional.empty());
        when(llmJsonClient.requestJson(
                eq(GenerationStageNames.STORY_BIBLE),
                eq("SYSTEM"),
                any(String.class)))
                .thenReturn(toJson(invalidBible));

        assertThatThrownBy(() -> generator.generate(job, project, digests, options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("char_001");
        ArgumentCaptor<GenerationStageResult> captor = ArgumentCaptor.forClass(GenerationStageResult.class);
        verify(repository).save(captor.capture());
        GenerationStageResult saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(saved.getStageName()).isEqualTo(GenerationStageNames.STORY_BIBLE);
        assertThat(saved.getErrorMessage()).contains("char_001");
    }

    /**
     * 验证地点 ID 重复时会记录失败阶段。
     */
    @Test
    void recordsFailedStageWhenLocationIdIsDuplicated() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        GenerationJob job = persistedJob(41L);
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        GenerationOptions options = GenerationOptions.defaults();
        List<ChapterDigest> digests = List.of(sampleDigest());
        StoryBible invalidBible = new StoryBible(
                List.of(new BibleCharacter("char_001", "主角", List.of(), "protagonist", "寻找真相", List.of(), "短句")),
                List.of(
                        new BibleLocation("loc_001", "旧书店", "interior", "昏暗"),
                        new BibleLocation("loc_001", "雨巷", "exterior", "潮湿")
                ),
                "主角寻找真相。",
                List.of("真相"),
                List.of()
        );
        when(repository.findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                eq(41L), eq(GenerationStageNames.STORY_BIBLE), eq(GenerationStatus.SUCCEEDED), any(String.class)))
                .thenReturn(Optional.empty());
        when(llmJsonClient.requestJson(
                eq(GenerationStageNames.STORY_BIBLE),
                eq("SYSTEM"),
                any(String.class)))
                .thenReturn(toJson(invalidBible));

        assertThatThrownBy(() -> generator.generate(job, project, digests, options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loc_001");
        ArgumentCaptor<GenerationStageResult> captor = ArgumentCaptor.forClass(GenerationStageResult.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(GenerationStatus.FAILED);
    }

    /**
     * 验证空章节摘要列表会在阶段查询前被拒绝。
     */
    @Test
    void rejectsEmptyDigestListBeforeStageLookup() {
        GenerationJob job = persistedJob(51L);
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");

        assertThatThrownBy(() -> generator.generate(job, project, List.of(), GenerationOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                any(), any(), any(), any());
    }

    /**
     * 构造故事圣经测试用的章节摘要。
     *
     * @return 章节摘要样例。
     */
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

    /**
     * 构造满足稳定 ID 约束的故事圣经样例。
     *
     * @return 故事圣经样例。
     */
    private static StoryBible sampleBible() {
        return new StoryBible(
                List.of(
                        new BibleCharacter("char_001", "林安", List.of("她"), "protagonist",
                                "寻找父亲失踪真相", List.of("克制", "敏感"), "短句为主"),
                        new BibleCharacter("char_002", "店主", List.of(), "supporting",
                                "隐藏部分真相", List.of("谨慎"), "语气平稳")
                ),
                List.of(new BibleLocation("loc_001", "旧书店", "interior", "昏暗狭窄")),
                "林安寻找父亲失踪真相。",
                List.of("真相", "选择"),
                List.of("第七章前不能揭露父亲身份")
        );
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
     * 将故事圣经转为 JSON，便于模拟缓存命中的阶段输出。
     *
     * @param bible 故事圣经。
     * @return JSON 文本。
     */
    private static String toJson(StoryBible bible) {
        try {
            return new ObjectMapper().writeValueAsString(bible);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * 构造与生产代码一致的故事圣经输入快照，用于计算预期缓存哈希。
     *
     * @param project 小说改编项目。
     * @param chapterDigests 章节摘要列表。
     * @param options 生成参数。
     * @return 哈希输入快照。
     */
    private static StoryBibleInput storyBibleInput(NovelProject project, List<ChapterDigest> chapterDigests,
                                                   GenerationOptions options) {
        return new StoryBibleInput(
                project.getId(),
                project.getTitle(),
                chapterDigests,
                options.format(),
                options.tone(),
                options.dialogueDensity(),
                options.narrationRetention(),
                options.hasAdditionalInstructions(),
                options.additionalInstructions()
        );
    }

    /**
     * 故事圣经测试使用的输入快照结构。
     *
     * @param projectId 项目主键。
     * @param projectTitle 项目标题。
     * @param chapterDigests 章节摘要列表。
     * @param format 剧本形式。
     * @param tone 整体风格。
     * @param dialogueDensity 对白密度。
     * @param narrationRetention 旁白保留度。
     * @param hasAdditionalInstructions 是否包含补充要求。
     * @param additionalInstructions 补充要求。
     */
    private record StoryBibleInput(
            Long projectId,
            String projectTitle,
            List<ChapterDigest> chapterDigests,
            String format,
            String tone,
            int dialogueDensity,
            int narrationRetention,
            boolean hasAdditionalInstructions,
            String additionalInstructions
    ) {
    }
}
