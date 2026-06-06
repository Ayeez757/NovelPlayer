package com.novelplayer.application.generation;

import com.novelplayer.domain.generation.GenerationJob;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖生成输入快照记录器，确保任务输入会作为阶段结果写入。
 */
class GenerationInputSnapshotRecorderTest {

    /**
     * 验证输入快照会计算哈希并通过阶段存储保存。
     */
    @Test
    void recordsSnapshotThroughStageStore() {
        GenerationStageStore stageStore = mock(GenerationStageStore.class);
        when(stageStore.sha256OfJson(any())).thenReturn("hash-001");

        GenerationInputSnapshotRecorder recorder = new GenerationInputSnapshotRecorder(stageStore);
        NovelProject project = new NovelProject("Rain Night", "chapter one\nchapter two");
        List<NovelChapter> chapters = List.of(
                new NovelChapter(project, 1, "Chapter 1", "content 1"),
                new NovelChapter(project, 2, "Chapter 2", "content 2")
        );
        GenerationJob job = persistedJob(11L, project);
        GenerationOptions options = new GenerationOptions("web_drama", "suspense", 60, 30, " keep pace ");

        recorder.record(job, project, chapters, options);

        verify(stageStore).saveSucceeded(eq(job), eq(GenerationStageNames.GENERATION_INPUT), eq("hash-001"), any());
    }

    /**
     * 构造带主键的生成任务，模拟已经持久化的实体。
     *
     * @param id 任务主键。
     * @param project 所属项目。
     * @return 已设置主键的生成任务。
     */
    private static GenerationJob persistedJob(Long id, NovelProject project) {
        GenerationJob job = new GenerationJob(project);
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
