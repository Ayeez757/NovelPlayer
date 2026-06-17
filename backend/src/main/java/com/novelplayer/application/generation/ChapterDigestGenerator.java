package com.novelplayer.application.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelplayer.ai.LlmJsonClient;
import com.novelplayer.application.generation.model.ChapterDigest;
import com.novelplayer.application.generation.prompt.ChapterDigestPromptBuilder;
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
 * 章节摘要阶段生成器（基于 LLM JSON 客户端）。
 *
 * <p>该实现通过 {@link LlmJsonClient} 直接与大语言模型交互，生成器自身负责提示词构建、
 * JSON 响应解析和数据规范化。每一章摘要只依赖当前项目、当前章节和生成选项，
 * 章节之间不存在生成结果依赖，因此可以安全地做有界并行。</p>
 *
 * 负责自己本阶段的：
 * 组装阶段输入
 * 构造 prompt
 * 调 LlmJsonClient.requestJson(...)
 * 自己做 JSON 映射、normalize、validate
 *
 * <p>结果仍按输入章节顺序返回，保证后续故事圣经和场景规划阶段看到稳定的章节顺序。</p>
 *
 * @see LlmJsonClient
 * @see ChapterDigest
 */
@Service
@ConditionalOnBean(LlmJsonClient.class)
@ConditionalOnProperty(prefix = "novel-player.generation", name = "pipeline-mode", havingValue = "staged",
        matchIfMissing = true)
public class ChapterDigestGenerator {

    private static final Logger log = LoggerFactory.getLogger(ChapterDigestGenerator.class);

    private final LlmJsonClient llmJsonClient;

    private final ObjectMapper objectMapper;

    private final ChapterDigestPromptBuilder promptBuilder;

    private final GenerationStageStore stageStore;
    private final ObjectProvider<GenerationJobLifecycleService> lifecycleServiceProvider;
    private final NovelPlayerProperties properties;
    private final GenerationStageParallelExecutor parallelExecutor;

//  构造器
public ChapterDigestGenerator(LlmJsonClient llmJsonClient,
                              ObjectMapper objectMapper,
                              GenerationStageStore stageStore,
                              ObjectProvider<GenerationJobLifecycleService> lifecycleServiceProvider,
                              NovelPlayerProperties properties,
                              GenerationStageParallelExecutor parallelExecutor,
                              ChapterDigestPromptBuilder promptBuilder) {
    this.llmJsonClient = Objects.requireNonNull(llmJsonClient, "LLM JSON 客户端不能为空");
    this.objectMapper = Objects.requireNonNull(objectMapper, "JSON 序列化工具不能为空");
    this.stageStore = Objects.requireNonNull(stageStore, "生成阶段存储不能为空");
    this.lifecycleServiceProvider = Objects.requireNonNull(
            lifecycleServiceProvider, "任务生命周期服务提供者不能为空");
    this.properties = Objects.requireNonNull(properties, "应用配置不能为空");
    this.parallelExecutor = Objects.requireNonNull(parallelExecutor, "并行执行器不能为空");
    this.promptBuilder = Objects.requireNonNull(promptBuilder, "提示词构建器不能为空");
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

    /**
     * 串行生成章节摘要。
     *
     * <p>串行模式下，每生成一章摘要都会更新任务的当前阶段，便于前端实时展示进度。</p>
     *
     * @param job 当前生成任务。
     * @param project 小说改编项目。
     * @param chapters 按章节顺序排列的小说章节。
     * @param options 生成参数。
     * @return 章节摘要列表。
     */
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
     * <p>该方法会先检查是否已存在相同输入的缓存结果，若存在则直接复用。
     * 否则调用大语言模型生成新的章节摘要并持久化。</p>
     *
     * @param job 当前生成任务。
     * @param project 小说改编项目。
     * @param chapter 待处理的章节。
     * @param options 生成参数。
     * @param updateCurrentStage 是否把任务阶段推进到当前细粒度阶段；并行模式下应关闭。
     * @return 章节摘要对象。
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
            log.info("调用 LLM 生成章节摘要 jobId={} projectId={} chapterIndex={} stageName={} inputHash={}",
                    job.getId(), project.getId(), chapter.getChapterIndex(), stageName, inputHash);
            ChapterDigest digest = requestChapterDigest(project, chapter, options);
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
     * 调用 LLM 生成单章摘要。
     *
     * 构建提示词 → 调用 LLM JSON 客户端 → 规范化响应 → 反序列化为 {@link ChapterDigest}。
     *
     * @param project 小说改编项目。
     * @param chapter 待处理的章节。
     * @param options 生成参数。
     * @return 章节摘要对象。
     */
    private ChapterDigest requestChapterDigest(NovelProject project, NovelChapter chapter, GenerationOptions options) {
        String inputJson = StageJsonSupport.toPrettyJson(
                objectMapper,
                ChapterDigestInput.from(project, chapter, options)
        );

        ChapterDigestPromptBuilder.PromptMessages prompt = promptBuilder.build(inputJson);

        String json = llmJsonClient.requestJson(
                GenerationStageNames.CHAPTER_DIGEST,
                prompt.systemPrompt(),
                prompt.userPrompt()
        );

        ObjectNode root = StageJsonSupport.readObject(objectMapper, GenerationStageNames.CHAPTER_DIGEST, json);
        normalizeChapterDigest(root, chapter);
        return StageJsonSupport.treeToValue(objectMapper, "章节摘要", root, ChapterDigest.class);
    }

    /**
     * 将当前细粒度阶段写回任务表，供串行模式下的前端轮询展示使用。
     *
     * @param job 当前生成任务。
     * @param stageName 阶段名称。
     */
    private void moveJobToStage(GenerationJob job, String stageName) {
        GenerationJobLifecycleService lifecycleService = lifecycleServiceProvider.getIfAvailable();
        if (lifecycleService != null && job.getId() != null) {
            lifecycleService.moveToStage(job.getId(), stageName);
        }
    }

    /**
     * 校验并复制章节列表，确保非空且不包含 null 元素。
     *
     * @param chapters 待校验的章节列表。
     * @return 不可变的章节列表副本。
     * @throws IllegalArgumentException 当列表为空或包含 null 元素时抛出。
     */
    private static List<NovelChapter> requireChapters(List<NovelChapter> chapters) {
        if (chapters == null || chapters.isEmpty()) {
            throw new IllegalArgumentException("章节列表不能为空");
        }
        return List.copyOf(chapters.stream()
                .map(chapter -> Objects.requireNonNull(chapter, "章节列表中不能包含 null 元素"))
                .toList());
    }

    /**
     * 章节摘要阶段的输入快照。
     *
     * <p>该快照只用于计算 inputHash。只要项目、章节正文或生成参数变化，就会得到不同哈希，
     * 从而避免复用过期的章节摘要。</p>
     *
     * @param projectId 项目 ID
     * @param projectTitle 项目标题
     * @param chapterIndex 章节索引
     * @param chapterTitle 章节标题
     * @param chapterContent 章节内容
     * @param format 输出格式
     * @param tone 语气风格
     * @param dialogueDensity 对话密度
     * @param narrationRetention 叙述保留度
     * @param hasAdditionalInstructions 是否有额外指令
     * @param additionalInstructions 额外指令内容
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
         * 从项目、章节和生成选项构建输入快照。
         *
         * @param project 小说改编项目。
         * @param chapter 章节对象。
         * @param options 生成选项。
         * @return 输入快照对象。
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

    /**
     * 规范化 AI 返回的章节摘要 JSON 数据。
     *
     * <p>该方法对 AI 输出进行以下处理：
     * <ul>
     *   <li>确保必填字段存在且有默认值</li>
     *   <li>将字符串数组中的对象类型转换为纯字符串</li>
     *   <li>确保 characters 和 locations 的结构完整</li>
     *   <li>为空数组提供默认占位内容</li>
     * </ul>
     * </p>
     *
     * @param root 待规范化的 JSON 根节点。
     * @param chapter 对应的章节对象，用于提供默认值。
     */
    private void normalizeChapterDigest(ObjectNode root, NovelChapter chapter) {
        StageJsonSupport.putIfBlank(root, "title", chapter.getTitle());
        StageJsonSupport.putIfBlank(root, "summary", StageJsonSupport.summarize(chapter.getContent(), 120));
        root.put("chapterIndex", chapter.getChapterIndex());

        ArrayNode majorEvents = StageJsonSupport.ensureArray(objectMapper, root, "majorEvents");
        StageJsonSupport.normalizeStringArray(objectMapper, majorEvents, "事件");
        if (majorEvents.isEmpty()) {
            majorEvents.add("概括本章的核心剧情转折点。");
        }

        ArrayNode conflicts = StageJsonSupport.ensureArray(objectMapper, root, "conflicts");
        ArrayNode openThreads = StageJsonSupport.ensureArray(objectMapper, root, "openThreads");
        ArrayNode adaptationHints = StageJsonSupport.ensureArray(objectMapper, root, "adaptationHints");
        StageJsonSupport.normalizeStringArray(objectMapper, conflicts, "冲突");
        StageJsonSupport.normalizeStringArray(objectMapper, openThreads, "悬念");
        StageJsonSupport.normalizeStringArray(objectMapper, adaptationHints, "改编提示");

        if (conflicts.isEmpty()) {
            conflicts.add("保留本章最核心的人物冲突。");
        }
        if (openThreads.isEmpty()) {
            openThreads.add("留下一个需要在后续场景中回应的悬念。");
        }
        if (adaptationHints.isEmpty()) {
            adaptationHints.add("把冲突最强的一处情节点直接转成可表演场面。");
        }

        ArrayNode characters = StageJsonSupport.ensureArray(objectMapper, root, "characters");
        for (int i = 0; i < characters.size(); i++) {
            JsonNode item = characters.get(i);
            if (item != null && item.isTextual()) {
                ObjectNode characterNode = objectMapper.createObjectNode();
                characterNode.put("name", item.asText());
                characterNode.putArray("aliases");
                characters.set(i, characterNode);
                item = characterNode;
            }
            if (item instanceof ObjectNode characterNode) {
                StageJsonSupport.putIfBlank(characterNode, "name", "角色 " + (i + 1));
                StageJsonSupport.ensureArray(objectMapper, characterNode, "aliases");
            }
        }

        ArrayNode locations = StageJsonSupport.ensureArray(objectMapper, root, "locations");
        for (int i = 0; i < locations.size(); i++) {
            JsonNode item = locations.get(i);
            if (item != null && item.isTextual()) {
                ObjectNode locationNode = objectMapper.createObjectNode();
                locationNode.put("name", item.asText());
                locations.set(i, locationNode);
                item = locationNode;
            }
            if (item instanceof ObjectNode locationNode) {
                StageJsonSupport.putIfBlank(locationNode, "name", "场景 " + (i + 1));
                StageJsonSupport.putIfBlank(locationNode, "type", "室内");
            }
        }
    }

}