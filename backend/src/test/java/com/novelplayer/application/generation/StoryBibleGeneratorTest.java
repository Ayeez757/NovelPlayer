package com.novelplayer.application.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelplayer.ai.StagedScriptAiClient;
import com.novelplayer.application.generation.model.BibleCharacter;
import com.novelplayer.application.generation.model.BibleLocation;
import com.novelplayer.application.generation.model.ChapterDigest;
import com.novelplayer.application.generation.model.StoryBible;
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
    private StagedScriptAiClient aiClient;

    @Mock
    private GenerationStageResultRepository repository;

    private GenerationStageStore stageStore;
    private StoryBibleGenerator generator;

    @BeforeEach
    void setUp() {
        stageStore = new GenerationStageStore(repository, new ObjectMapper());
        generator = new StoryBibleGenerator(aiClient, stageStore);
    }

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
        verify(aiClient, never()).generateStoryBible(any(), any(), any());
    }

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
        when(aiClient.generateStoryBible(project, digests, options)).thenReturn(bible);

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
        when(aiClient.generateStoryBible(project, digests, options)).thenReturn(invalidBible);

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
        when(aiClient.generateStoryBible(project, digests, options)).thenReturn(invalidBible);

        assertThatThrownBy(() -> generator.generate(job, project, digests, options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
        ArgumentCaptor<GenerationStageResult> captor = ArgumentCaptor.forClass(GenerationStageResult.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(GenerationStatus.FAILED);
    }

    @Test
    void rejectsEmptyDigestListBeforeStageLookup() {
        GenerationJob job = persistedJob(51L);
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");

        assertThatThrownBy(() -> generator.generate(job, project, List.of(), GenerationOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chapterDigests");
        verify(repository, never()).findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                any(), any(), any(), any());
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

    private static String toJson(StoryBible bible) {
        try {
            return new ObjectMapper().writeValueAsString(bible);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

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
