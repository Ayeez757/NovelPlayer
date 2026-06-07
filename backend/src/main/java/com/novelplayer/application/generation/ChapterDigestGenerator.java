package com.novelplayer.application.generation;

import com.novelplayer.ai.StagedScriptAiClient;
import com.novelplayer.application.generation.model.ChapterDigest;
import com.novelplayer.config.NovelPlayerProperties;
import com.novelplayer.domain.generation.GenerationJob;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 章节摘要阶段生成器。
 *
 * <p>每一章摘要只依赖当前项目、当前章节和生成选项，章节之间不存在生成结果依赖，因此可以安全地做有界并行。
 * 结果仍按输入章节顺序返回，保证后续 Story Bible 和场景规划阶段看到稳定的章节顺序。</p>
 */
@Service
@ConditionalOnBean(StagedScriptAiClient.class)
@ConditionalOnProperty(prefix = "novel-player.generation", name = "pipeline-mode", havingValue = "staged",
        matchIfMissing = true)
public class ChapterDigestGenerator {

    private static final Logger log = LoggerFactory.getLogger(ChapterDigestGenerator.class);

    private final StagedScriptAiClient aiClient;
    private final GenerationStageStore stageStore;
    private final ObjectProvider<GenerationJobLifecycleService> lifecycleServiceProvider;
    private final NovelPlayerProperties properties;
    private final GenerationStageParallelExecutor parallelExecutor;

    /**
     * 创建章节摘要阶段生成器。
     *
     * @param aiClient 阶段化 AI 客户端。
     * @param stageStore 生成阶段结果存取层。
     * @param lifecycleServiceProvider 任务生命周期服务，测试场景下可为空。
     * @param properties 应用配置。
     * @param parallelExecutor 阶段内有界并行执行器。
     */
    public ChapterDigestGenerator(StagedScriptAiClient aiClient,
                                  GenerationStageStore stageStore,
                                  ObjectProvider<GenerationJobLifecycleService> lifecycleServiceProvider,
                                  NovelPlayerProperties properties,
                                  GenerationStageParallelExecutor parallelExecutor) {
        this.aiClient = Objects.requireNonNull(aiClient, "aiClient must not be null");
        this.stageStore = Objects.requireNonNull(stageStore, "stageStore must not be null");
        this.lifecycleServiceProvider = Objects.requireNonNull(
                lifecycleServiceProvider, "lifecycleServiceProvider must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.parallelExecutor = Objects.requireNonNull(parallelExecutor, "parallelExecutor must not be null");
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
        int concurrency = Math.min(
                properties.getGeneration().getChapterDigestConcurrency(),
                normalizedChapters.size()
        );

        log.info("开始生成章节摘要 jobId={} projectId={} chapterCount={} concurrency={} format={} tone={}",
                job.getId(), project.getId(), normalizedChapters.size(), concurrency,
                options.format(), options.tone());

        List<ChapterDigest> results;
        if (concurrency == 1) {
            results = generateSerial(job, project, normalizedChapters, options);
        } else {
            /*
             * 并行模式不把 job.currentStage 改成 chapter_digest:1 这类细粒度值。
             * 多个线程同时写同一个任务阶段会产生抖动；前端进度通过 generation_stage_result 统计完成数。
             */
            results = parallelExecutor.runOrdered(
                    GenerationStageNames.CHAPTER_DIGEST,
                    normalizedChapters,
                    concurrency,
                    chapter -> generateOne(job, project, chapter, options, false)
            );
        }

        log.info("章节摘要阶段完成 jobId={} projectId={} chapterCount={} digestCount={}",
                job.getId(), project.getId(), normalizedChapters.size(), results.size());
        return List.copyOf(results);
    }

    private List<ChapterDigest> generateSerial(GenerationJob job, NovelProject project,
                                               List<NovelChapter> chapters, GenerationOptions options) {
        List<ChapterDigest> results = new ArrayList<>(chapters.size());
        for (NovelChapter chapter : chapters) {
            results.add(generateOne(job, project, chapter, options, true));
        }
        return results;
    }

    /**
     * 生成或复用单章摘要。
     *
     * @param updateCurrentStage 是否把任务阶段推进到当前细粒度阶段；并行模式下应关闭。
     */
    private ChapterDigest generateOne(GenerationJob job, NovelProject project, NovelChapter chapter,
                                      GenerationOptions options, boolean updateCurrentStage) {
        String stageName = GenerationStageNames.chapterDigest(chapter.getChapterIndex());
        if (updateCurrentStage) {
            moveJobToStage(job, stageName);
        }

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
                    job.getId(), project.getId(), chapter.getChapterIndex(), stageName,
                    exception.getMessage(), exception);
            throw exception;
        }
    }

    /**
     * 尽力把当前细粒度阶段写回任务表，供串行模式下的前端轮询展示使用。
     */
    private void moveJobToStage(GenerationJob job, String stageName) {
        GenerationJobLifecycleService lifecycleService = lifecycleServiceProvider.getIfAvailable();
        if (lifecycleService != null && job.getId() != null) {
            lifecycleService.moveToStage(job.getId(), stageName);
        }
    }

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
