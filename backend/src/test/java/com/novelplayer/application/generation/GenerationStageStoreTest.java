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

@ExtendWith(MockitoExtension.class)
class GenerationStageStoreTest {

    @Mock
    private GenerationStageResultRepository repository;

    private GenerationStageStore store;

    @BeforeEach
    void setUp() {
        store = new GenerationStageStore(repository, new ObjectMapper());
    }

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

    @Test
    void exposesSha256HelperForStableInputHashes() {
        assertThat(store.sha256Of("hello")).isEqualTo(store.sha256Of("hello"));
        assertThat(store.sha256Of("hello")).isNotEqualTo(store.sha256Of("world"));
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

    private static String toJson(SamplePayload payload) {
        try {
            return new ObjectMapper().writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record SamplePayload(String message, int version) {
    }
}
