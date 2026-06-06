package com.novelplayer.application.generation;

import com.novelplayer.domain.generation.GenerationJob;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 记录一次生成任务的输入参数快照。
 *
 * <p>快照只保存可审计的生成参数和章节数量，不重复保存完整小说原文或内部提示词。</p>
 */
@Component
public class GenerationInputSnapshotRecorder {

    private static final Logger log = LoggerFactory.getLogger(GenerationInputSnapshotRecorder.class);

    private final GenerationStageStore stageStore;

    /**
     * 创建生成输入快照记录器。
     *
     * @param stageStore 统一的生成阶段结果存取层。
     */
    public GenerationInputSnapshotRecorder(GenerationStageStore stageStore) {
        this.stageStore = stageStore;
    }

    /**
     * 将本次生成输入条件记录为一个成功阶段结果。
     *
     * @param job 生成任务，必须已经持久化。
     * @param project 小说改编项目。
     * @param chapters 本次参与生成的章节列表。
     * @param options 生成参数。
     */
    public void record(GenerationJob job, NovelProject project, List<NovelChapter> chapters, GenerationOptions options) {
        log.debug("开始记录生成输入快照 jobId={} projectId={} chapterCount={} format={} tone={} hasAdditionalInstructions={}",
                job.getId(), project.getId(), chapters.size(), options.format(), options.tone(),
                options.hasAdditionalInstructions());
        GenerationInputSnapshot snapshot = GenerationInputSnapshot.from(project, chapters, options);
        String inputHash = stageStore.sha256OfJson(snapshot);
        stageStore.saveSucceeded(job, GenerationStageNames.GENERATION_INPUT, inputHash, snapshot);
        log.info("生成输入快照记录完成 jobId={} projectId={} chapterCount={} inputHash={}",
                job.getId(), project.getId(), chapters.size(), inputHash);
    }

    /**
     * 生成输入快照的可审计数据结构。
     *
     * @param projectId 项目主键。
     * @param title 作品标题。
     * @param chapterCount 参与生成的章节数量。
     * @param format 剧本形式。
     * @param tone 整体风格。
     * @param dialogueDensity 对白密度。
     * @param narrationRetention 旁白保留度。
     * @param hasAdditionalInstructions 是否包含用户补充要求。
     * @param additionalInstructions 用户补充要求。
     */
    private record GenerationInputSnapshot(
            Long projectId,
            String title,
            int chapterCount,
            String format,
            String tone,
            int dialogueDensity,
            int narrationRetention,
            boolean hasAdditionalInstructions,
            String additionalInstructions
    ) {
        /**
         * 从当前生成上下文构造输入快照。
         *
         * @param project 小说改编项目。
         * @param chapters 本次参与生成的章节列表。
         * @param options 生成参数。
         * @return 可持久化的输入快照。
         */
        private static GenerationInputSnapshot from(NovelProject project, List<NovelChapter> chapters,
                                                    GenerationOptions options) {
            return new GenerationInputSnapshot(
                    project.getId(),
                    project.getTitle(),
                    chapters.size(),
                    options.format(),
                    options.tone(),
                    options.dialogueDensity(),
                    options.narrationRetention(),
                    options.hasAdditionalInstructions(),
                    options.additionalInstructions()
            );
        }
    }
}
