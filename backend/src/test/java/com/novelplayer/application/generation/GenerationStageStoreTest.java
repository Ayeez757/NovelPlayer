package com.novelplayer.application.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖生成阶段存储的序列化、缓存读取、失败记录和输入校验。
 */
@ExtendWith(MockitoExtension.class)
class GenerationStageStoreTest {

    @Mock
    private GenerationStageResultRepository repository;

    private GenerationStageStore store;

    /**
     * 为每个用例创建独立的阶段存储实例。
     */
    @BeforeEach
    void setUp() {
        store = new GenerationStageStore(repository, new ObjectMapper());
    }

    /**
     * 验证成功阶段会序列化输出并按规范化后的阶段名、哈希保存。
     */
    @Test
    void savesSucceededStageWithSerializedPayload() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        GenerationJob job = persistedJob(42L);
        SamplePayload payload = new SamplePayload("hello", 3);

        SamplePayload returned = store.saveSucceeded(job, "story_bible", " hash-1 ", payload);

        assertThat(returned).isSameAs(payload);
        ArgumentCaptor<GenerationStageResult> captor = ArgumentCaptor.forClass(GenerationStageResult.class);
        verify(repository).save(captor.capture());
        GenerationStageResult saved = captor.getValue();
        assertThat(saved.getJob().getId()).isEqualTo(42L);
        assertThat(saved.getStageName()).isEqualTo("story_bible");
        assertThat(saved.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(saved.getInputHash()).isEqualTo("hash-1");
        assertThat(saved.getOutputJson()).contains("\"message\":\"hello\"");
        assertThat(saved.getErrorMessage()).isNull();
    }

    /**
     * 验证成功阶段可从 JSON 反序列化回指定载荷类型。
     */
    @Test
    void readsSucceededStageBackIntoTypedPayload() {
        GenerationJob job = persistedJob(7L);
        SamplePayload payload = new SamplePayload("world", 9);
        String inputHash = "abc123";
        String json = store.sha256OfJson(payload); // ensures serializer is exercised
        String outputJson = toJson(payload);
        GenerationStageResult result = new GenerationStageResult(
                job,
                "scene_draft:scene_001",
                GenerationStatus.SUCCEEDED,
                inputHash,
                outputJson,
                null
        );
        when(repository.findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                7L, "scene_draft:scene_001", GenerationStatus.SUCCEEDED, inputHash))
                .thenReturn(Optional.of(result));

        Optional<SamplePayload> loaded = store.findSucceeded(job, "scene_draft:scene_001", inputHash, SamplePayload.class);

        assertThat(loaded).isPresent();
        assertThat(loaded.orElseThrow()).isEqualTo(payload);
        assertThat(json).isNotBlank();
    }

    /**
     * 验证同一输入哈希下存在成功结果时会被视为可复用。
     */
    @Test
    void reportsReusableStageWhenSucceededResultExistsWithSameHash() {
        GenerationJob job = persistedJob(8L);
        String inputHash = "same-input";
        GenerationStageResult result = new GenerationStageResult(
                job,
                "chapter_digest:1",
                GenerationStatus.SUCCEEDED,
                inputHash,
                "{}",
                null
        );
        when(repository.findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                8L, "chapter_digest:1", GenerationStatus.SUCCEEDED, inputHash))
                .thenReturn(Optional.of(result));

        boolean reusable = store.hasSucceeded(job, "chapter_digest:1", inputHash);

        assertThat(reusable).isTrue();
    }

    /**
     * 验证失败阶段会保存清理后的错误消息，并允许哈希为空。
     */
    @Test
    void recordsFailedStageWithTrimmedMessageAndNullableHash() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        GenerationJob job = persistedJob(99L);

        store.saveFailed(job, "scene_plan", null, "  boom  ");

        ArgumentCaptor<GenerationStageResult> captor = ArgumentCaptor.forClass(GenerationStageResult.class);
        verify(repository).save(captor.capture());
        GenerationStageResult saved = captor.getValue();
        assertThat(saved.getStageName()).isEqualTo("scene_plan");
        assertThat(saved.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(saved.getInputHash()).isNull();
        assertThat(saved.getOutputJson()).isNull();
        assertThat(saved.getErrorMessage()).isEqualTo("boom");
    }

    /**
     * 验证阶段存储会拒绝未持久化任务、空阶段名和空输入哈希。
     */
    @Test
    void validatesStageNameAndJobPresence() {
        GenerationJob job = new GenerationJob(new NovelProject("title", "source"));

        assertThatThrownBy(() -> store.saveSucceeded(job, "stage", "hash", new SamplePayload("x", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("persisted");
        assertThatThrownBy(() -> store.saveSucceeded(persistedJob(1L), " ", "hash", new SamplePayload("x", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stageName");
        assertThatThrownBy(() -> store.saveSucceeded(persistedJob(1L), "stage", " ", new SamplePayload("x", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputHash");
    }

    /**
     * 验证 SHA-256 工具对相同输入稳定、对不同输入敏感。
     */
    @Test
    void exposesSha256HelperForStableInputHashes() {
        assertThat(store.sha256Of("hello")).isEqualTo(store.sha256Of("hello"));
        assertThat(store.sha256Of("hello")).isNotEqualTo(store.sha256Of("world"));
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
     * 将样例载荷转为 JSON，便于模拟数据库中保存的阶段输出。
     *
     * @param payload 样例载荷。
     * @return JSON 文本。
     */
    private static String toJson(SamplePayload payload) {
        try {
            return new ObjectMapper().writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * 阶段存储测试使用的最小 JSON 载荷。
     *
     * @param message 测试消息。
     * @param version 测试版本号。
     */
    private record SamplePayload(String message, int version) {
    }
}
