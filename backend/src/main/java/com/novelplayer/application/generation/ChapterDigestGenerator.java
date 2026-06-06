package com.novelplayer.application.generation;

import com.novelplayer.ai.StagedScriptAiClient;
import com.novelplayer.application.generation.model.ChapterDigest;
import com.novelplayer.domain.generation.GenerationJob;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 章节摘要阶段生成器。
 *
 * <p>该服务是长篇小说多阶段生成流水线的第一个内容处理阶段。它按章节逐一生成
 * {@link ChapterDigest}，并通过 {@link GenerationStageStore} 复用同一输入哈希下已经成功的结果。</p>
 */
@Service
@ConditionalOnProperty(prefix = "novel-player.generation", name = "pipeline-mode", havingValue = "staged",
        matchIfMissing = true)
public class ChapterDigestGenerator {

    private static final Logger log = LoggerFactory.getLogger(ChapterDigestGenerator.class);

    private final StagedScriptAiClient aiClient;
    private final GenerationStageStore stageStore;

    /**
     * 创建章节摘要阶段生成器。
     *
     * @param aiClient 阶段化 AI 客户端。
     * @param stageStore 生成阶段结果存取层。
     */
    public ChapterDigestGenerator(StagedScriptAiClient aiClient, GenerationStageStore stageStore) {
        this.aiClient = aiClient;
        this.stageStore = stageStore;
    }

    /**
     * 按章节顺序生成或复用章节摘要。
     *
     * @param job 当前生成任务，必须已经持久化。
     * @param project 小说改编项目。
     * @param chapters 按章节顺序排列的小说章节。
     * @param options 生成参数。
     * @return 与输入章节顺序一致的章节摘要列表。
     */
    public List<ChapterDigest> generate(GenerationJob job, NovelProject project, List<NovelChapter> chapters,
                                        GenerationOptions options) {
        Objects.requireNonNull(job, "job must not be null");
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(options, "options must not be null");
        List<NovelChapter> normalizedChapters = requireChapters(chapters);

        log.info("开始生成章节摘要 jobId={} projectId={} chapterCount={} format={} tone={}",
                job.getId(), project.getId(), normalizedChapters.size(), options.format(), options.tone());

        List<ChapterDigest> results = new ArrayList<>(normalizedChapters.size());
        for (NovelChapter chapter : normalizedChapters) {
            results.add(generateOne(job, project, chapter, options));
        }

        log.info("章节摘要阶段完成 jobId={} projectId={} chapterCount={} digestCount={}",
                job.getId(), project.getId(), normalizedChapters.size(), results.size());
        return List.copyOf(results);
    }

    /**
     * 生成或复用单章摘要。
     *
     * @param job 当前生成任务。
     * @param project 小说改编项目。
     * @param chapter 待处理章节。
     * @param options 生成参数。
     * @return 单章摘要。
     */
    private ChapterDigest generateOne(GenerationJob job, NovelProject project, NovelChapter chapter,
                                      GenerationOptions options) {
        String stageName = GenerationStageNames.chapterDigest(chapter.getChapterIndex());
        String inputHash = stageStore.sha256OfJson(ChapterDigestInput.from(project, chapter, options));

        Optional<ChapterDigest> cached = stageStore.findSucceeded(job, stageName, inputHash, ChapterDigest.class);
        if (cached.isPresent()) {
            log.info("复用已存在的章节摘要 jobId={} projectId={} chapterIndex={} stageName={} inputHash={}",
                    job.getId(), project.getId(), chapter.getChapterIndex(), stageName, inputHash);
            return cached.orElseThrow();
        }

        try {
            log.info("调用阶段化 AI 生成章节摘要 jobId={} projectId={} chapterIndex={} stageName={} inputHash={}",
                    job.getId(), project.getId(), chapter.getChapterIndex(), stageName, inputHash);
            ChapterDigest digest = aiClient.generateChapterDigest(project, chapter, options);
            stageStore.saveSucceeded(job, stageName, inputHash, digest);
            log.info("章节摘要生成并保存成功 jobId={} projectId={} chapterIndex={} stageName={} summaryLength={}",
                    job.getId(), project.getId(), chapter.getChapterIndex(), stageName, digest.summary().length());
            return digest;
        } catch (RuntimeException exception) {
            stageStore.saveFailed(job, stageName, inputHash, exception.getMessage());
            log.warn("章节摘要生成失败 jobId={} projectId={} chapterIndex={} stageName={} error={}",
                    job.getId(), project.getId(), chapter.getChapterIndex(), stageName, exception.getMessage(), exception);
            throw exception;
        }
    }

    /**
     * 校验章节列表，并复制为不可变列表。
     *
     * @param chapters 原始章节列表。
     * @return 不可变章节列表。
     */
    private static List<NovelChapter> requireChapters(List<NovelChapter> chapters) {
        if (chapters == null || chapters.isEmpty()) {
            throw new IllegalArgumentException("chapters must not be empty");
        }
        return List.copyOf(chapters.stream()
                .map(chapter -> Objects.requireNonNull(chapter, "chapters must not contain null"))
                .toList());
    }

    /**
     * 章节摘要阶段的输入快照。
     *
     * <p>该快照只用于计算 inputHash。只要项目、章节正文或生成参数变化，就会得到不同哈希，
     * 从而避免复用过期的章节摘要。</p>
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
     * @param hasAdditionalInstructions 是否存在用户补充要求。
     * @param additionalInstructions 用户补充要求。
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
        /**
         * 从当前章节摘要上下文构造输入快照。
         *
         * @param project 小说改编项目。
         * @param chapter 待处理章节。
         * @param options 生成参数。
         * @return 用于哈希计算的输入快照。
         */
        private static ChapterDigestInput from(NovelProject project, NovelChapter chapter, GenerationOptions options) {
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
    }
}
