package com.novelplayer.application.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelplayer.ai.LlmJsonClient;
import com.novelplayer.application.generation.model.BibleCharacter;
import com.novelplayer.application.generation.model.BibleLocation;
import com.novelplayer.application.generation.model.DraftSceneBlock;
import com.novelplayer.application.generation.model.PlannedScene;
import com.novelplayer.application.generation.model.SceneDraft;
import com.novelplayer.application.generation.model.SceneDraftContext;
import com.novelplayer.application.generation.model.ScenePlan;
import com.novelplayer.application.generation.model.StoryBible;
import com.novelplayer.application.generation.prompt.SceneDraftPromptBuilder;
import com.novelplayer.config.NovelPlayerProperties;
import com.novelplayer.domain.generation.GenerationJob;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 分场草稿阶段生成器（基于 LLM JSON 客户端）。
 *
 * <p>该实现通过 {@link LlmJsonClient} 直接与大语言模型交互，生成器自身负责提示词构建、
 * JSON 响应解析和数据规范化。分场草稿将场景规划中的结构大纲填充为具体的动作描述、
 * 对白和转场等可表演的内容块。</p>
 *
 * <p>每个场景草稿独立生成，场景之间不存在生成结果依赖，因此可以安全地做有界并行。
 * 但为了保持叙事连贯性，并行模式下仍会传入前一个场景的摘要作为参考上下文。</p>
 *
 * @see LlmJsonClient
 * @see SceneDraft
 * @see ScenePlan
 */
@Service
@ConditionalOnBean(LlmJsonClient.class)
@ConditionalOnProperty(prefix = "novel-player.generation", name = "pipeline-mode", havingValue = "staged",
        matchIfMissing = true)
public class SceneDraftGenerator {

    private static final Logger log = LoggerFactory.getLogger(SceneDraftGenerator.class);

    /**
     * 阶段化系统提示词，要求大语言模型只输出合法 JSON。
     * 同时要求角色 ID、地点 ID 和源章节必须与输入的 plannedScene 保持一致。
     */
    private final SceneDraftPromptBuilder promptBuilder;
    private final LlmJsonClient llmJsonClient;
    private final ObjectMapper objectMapper;
    private final GenerationStageStore stageStore;
    private final ObjectProvider<GenerationJobLifecycleService> lifecycleServiceProvider;
    private final NovelPlayerProperties properties;
    private final GenerationStageParallelExecutor parallelExecutor;
    public SceneDraftGenerator(LlmJsonClient llmJsonClient,
                               ObjectMapper objectMapper,
                               GenerationStageStore stageStore,
                               ObjectProvider<GenerationJobLifecycleService> lifecycleServiceProvider,
                               NovelPlayerProperties properties,
                               GenerationStageParallelExecutor parallelExecutor,
                               SceneDraftPromptBuilder promptBuilder) {
        this.llmJsonClient = Objects.requireNonNull(llmJsonClient, "llmJsonClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.stageStore = Objects.requireNonNull(stageStore, "stageStore must not be null");
        this.lifecycleServiceProvider = Objects.requireNonNull(
                lifecycleServiceProvider, "lifecycleServiceProvider must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.parallelExecutor = Objects.requireNonNull(parallelExecutor, "parallelExecutor must not be null");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder must not be null");
    }

    /**
     * 按场景顺序生成或复用分场草稿。
     *
     * <p>分场草稿是剧本创作的核心阶段，将场景规划中的结构大纲转化为具体的
     * 动作描述、对白、转场等可表演内容块。每个场景草稿独立生成，
     * 结果按输入场景顺序返回。</p>
     *
     * @param job 当前生成任务，必须已经持久化。
     * @param project 小说改编项目。
     * @param chapters 按章节顺序排列的小说章节。
     * @param scenePlan 场景规划，包含待生成的所有场景结构大纲。
     * @param storyBible 故事圣经，包含全局角色和场景定义。
     * @param options 生成参数。
     * @return 与场景规划顺序一致的分场草稿列表。
     */
    public List<SceneDraft> generate(GenerationJob job, NovelProject project, List<NovelChapter> chapters,
                                     ScenePlan scenePlan, StoryBible storyBible, GenerationOptions options) {
        Objects.requireNonNull(job, "job must not be null");
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(scenePlan, "scenePlan must not be null");
        Objects.requireNonNull(storyBible, "storyBible must not be null");
        Objects.requireNonNull(options, "options must not be null");
        Map<Integer, NovelChapter> chaptersByIndex = indexChapters(chapters);
        Map<String, BibleCharacter> charactersById = indexCharacters(storyBible.characters());
        Map<String, BibleLocation> locationsById = indexLocations(storyBible.locations());
        validateSceneIds(scenePlan);
        int concurrency = Math.min(
                properties.getGeneration().getSceneDraftConcurrency(),
                scenePlan.scenes().size()
        );

        log.info("开始生成分场草稿 jobId={} projectId={} sceneCount={} chapterCount={} characterCount={} locationCount={} concurrency={}",
                job.getId(), project.getId(), scenePlan.scenes().size(), chaptersByIndex.size(),
                charactersById.size(), locationsById.size(), concurrency);

        List<SceneDraft> drafts;
        if (concurrency == 1) {
            drafts = generateSerial(job, project, scenePlan.scenes(), chaptersByIndex, charactersById,
                    locationsById, storyBible.continuityRules(), options);
        } else {
            drafts = generateParallel(job, project, scenePlan.scenes(), chaptersByIndex, charactersById,
                    locationsById, storyBible.continuityRules(), options, concurrency);
        }

        log.info("分场草稿阶段完成 jobId={} projectId={} sceneCount={} draftCount={}",
                job.getId(), project.getId(), scenePlan.scenes().size(), drafts.size());
        return List.copyOf(drafts);
    }

    /**
     * 串行生成分场草稿。
     *
     * <p>串行模式下，每生成一场草稿都会更新任务的当前阶段，便于前端实时展示进度。
     * 同时会将前一场的摘要传递给下一场，保持叙事连贯性。</p>
     *
     * @param job 当前生成任务。
     * @param project 小说改编项目。
     * @param scenes 场景规划列表。
     * @param chaptersByIndex 章节索引映射。
     * @param charactersById 角色 ID 映射。
     * @param locationsById 地点 ID 映射。
     * @param continuityRules 连贯性规则列表。
     * @param options 生成参数。
     * @return 分场草稿列表。
     */
    private List<SceneDraft> generateSerial(GenerationJob job, NovelProject project, List<PlannedScene> scenes,
                                            Map<Integer, NovelChapter> chaptersByIndex,
                                            Map<String, BibleCharacter> charactersById,
                                            Map<String, BibleLocation> locationsById,
                                            List<String> continuityRules,
                                            GenerationOptions options) {
        List<SceneDraft> drafts = new ArrayList<>(scenes.size());
        String previousSceneSummary = null;
        for (PlannedScene scene : scenes) {
            SceneDraft draft = generateOne(job, project, scene, chaptersByIndex, charactersById,
                    locationsById, continuityRules, previousSceneSummary, options, true);
            drafts.add(draft);
            previousSceneSummary = draft.summary();
        }
        return drafts;
    }

    /**
     * 并行生成分场草稿。
     *
     * <p>并行模式下不更新任务的当前阶段，避免多线程竞争。前端进度通过
     * generation_stage_result 统计完成数。</p>
     *
     * @param job 当前生成任务。
     * @param project 小说改编项目。
     * @param scenes 场景规划列表。
     * @param chaptersByIndex 章节索引映射。
     * @param charactersById 角色 ID 映射。
     * @param locationsById 地点 ID 映射。
     * @param continuityRules 连贯性规则列表。
     * @param options 生成参数。
     * @param concurrency 并发度。
     * @return 分场草稿列表。
     */
    private List<SceneDraft> generateParallel(GenerationJob job, NovelProject project, List<PlannedScene> scenes,
                                              Map<Integer, NovelChapter> chaptersByIndex,
                                              Map<String, BibleCharacter> charactersById,
                                              Map<String, BibleLocation> locationsById,
                                              List<String> continuityRules,
                                              GenerationOptions options,
                                              int concurrency) {
        List<SceneTask> tasks = new ArrayList<>(scenes.size());
        for (int index = 0; index < scenes.size(); index++) {
            String previousPlannedSummary = index == 0 ? null : scenes.get(index - 1).summary();
            tasks.add(new SceneTask(scenes.get(index), previousPlannedSummary));
        }

        return parallelExecutor.runOrdered(
                GenerationStageNames.SCENE_DRAFT,
                tasks,
                concurrency,
                task -> generateOne(job, project, task.scene(), chaptersByIndex, charactersById,
                        locationsById, continuityRules, task.previousSceneSummary(), options, false)
        );
    }

    /**
     * 生成或复用单场草稿。
     *
     * <p>该方法会先检查是否已存在相同输入的缓存结果，若存在则直接复用。
     * 否则调用大语言模型生成新的分场草稿并持久化。</p>
     *
     * @param job 当前生成任务。
     * @param project 小说改编项目。
     * @param scene 待生成的场景规划。
     * @param chaptersByIndex 章节索引映射。
     * @param charactersById 角色 ID 映射。
     * @param locationsById 地点 ID 映射。
     * @param continuityRules 连贯性规则列表。
     * @param previousSceneSummary 前一场摘要，用于保持连贯性。
     * @param options 生成参数。
     * @param updateCurrentStage 是否把任务阶段推进到当前细粒度阶段；并行模式下应关闭。
     * @return 分场草稿对象。
     */
    private SceneDraft generateOne(GenerationJob job, NovelProject project, PlannedScene scene,
                                   Map<Integer, NovelChapter> chaptersByIndex,
                                   Map<String, BibleCharacter> charactersById,
                                   Map<String, BibleLocation> locationsById,
                                   List<String> continuityRules,
                                   @Nullable String previousSceneSummary,
                                   GenerationOptions options,
                                   boolean updateCurrentStage) {
        String stageName = GenerationStageNames.sceneDraft(scene.id());
        if (updateCurrentStage) {
            moveJobToStage(job, stageName);
        }
        String inputHash = null;
        try {
            SceneDraftContext context = buildContext(scene, chaptersByIndex, charactersById,
                    locationsById, continuityRules, previousSceneSummary);
            inputHash = stageStore.sha256OfJson(SceneDraftInput.from(project, context, options));
            log.info("开始生成单场草稿 jobId={} projectId={} sceneId={} stageName={} inputHash={} sourceChapterCount={} characterCount={}",
                    job.getId(), project.getId(), scene.id(), stageName, inputHash,
                    context.sourceChapters().size(), context.characters().size());

            Optional<SceneDraft> cached = stageStore.findSucceeded(job, stageName, inputHash, SceneDraft.class);
            if (cached.isPresent()) {
                SceneDraft draft = cached.orElseThrow();
                validateDraft(draft, context);
                log.info("复用已存在的分场草稿 jobId={} projectId={} sceneId={} blockCount={}",
                        job.getId(), project.getId(), scene.id(), draft.blocks().size());
                return draft;
            }

            SceneDraft draft = requestSceneDraft(project, context, options);
            validateDraft(draft, context);
            stageStore.saveSucceeded(job, stageName, inputHash, draft);
            log.info("分场草稿生成并保存成功 jobId={} projectId={} sceneId={} blockCount={} summaryLength={}",
                    job.getId(), project.getId(), scene.id(), draft.blocks().size(), draft.summary().length());
            return draft;
        } catch (RuntimeException exception) {
            stageStore.saveFailed(job, stageName, inputHash, exception.getMessage());
            log.warn("分场草稿生成失败 jobId={} projectId={} sceneId={} stageName={} error={}",
                    job.getId(), project.getId(), scene.id(), stageName, exception.getMessage(), exception);
            throw exception;
        }
    }

    /**
     * 调用 LLM 生成单场草稿。
     *
     * 构建提示词 → 调用 LLM JSON 客户端 → 规范化响应 → 反序列化为 {@link SceneDraft}。
     *
     * @param project 小说改编项目。
     * @param context 分场写作上下文，包含场景规划、源章节、角色、地点等信息。
     * @param options 生成参数。
     * @return 分场草稿对象。
     */
    private SceneDraft requestSceneDraft(NovelProject project, SceneDraftContext context, GenerationOptions options) {
        String inputJson = StageJsonSupport.toPrettyJson(
                objectMapper,
                SceneDraftInput.from(project, context, options)
        );

        SceneDraftPromptBuilder.PromptMessages prompt = promptBuilder.build(inputJson);

        String json = llmJsonClient.requestJson(
                GenerationStageNames.SCENE_DRAFT,
                prompt.systemPrompt(),
                prompt.userPrompt()
        );

        ObjectNode root = StageJsonSupport.readObject(objectMapper, GenerationStageNames.SCENE_DRAFT, json);
        normalizeSceneDraft(root, context);
        return StageJsonSupport.treeToValue(objectMapper, "分场草稿", root, SceneDraft.class);
    }

    /**
     * 规范化 AI 返回的分场草稿 JSON 数据。
     *
     * @param root 待规范化的 JSON 根节点。
     * @param context 分场写作上下文，用于提供默认值。
     */
    private void normalizeSceneDraft(ObjectNode root, SceneDraftContext context) {
        PlannedScene scene = context.plannedScene();

        StageJsonSupport.putIfBlank(root, "id", scene.id());
        StageJsonSupport.putIfBlank(root, "title", scene.title());
        StageJsonSupport.putIfBlank(root, "locationId", scene.locationId());
        StageJsonSupport.putIfBlank(root, "timeOfDay", scene.timeOfDay());
        StageJsonSupport.putIfBlank(root, "dramaticPurpose", scene.dramaticPurpose());
        StageJsonSupport.putIfBlank(root, "summary", scene.summary());

        ArrayNode sourceChapters = StageJsonSupport.ensureArray(objectMapper, root, "sourceChapters");
        if (sourceChapters.isEmpty()) {
            scene.sourceChapters().forEach(sourceChapters::add);
        }

        ArrayNode characters = StageJsonSupport.ensureArray(objectMapper, root, "characters");
        if (characters.isEmpty()) {
            scene.characters().forEach(characters::add);
        }

        ArrayNode blocks = StageJsonSupport.ensureArray(objectMapper, root, "blocks");
        if (blocks.isEmpty()) {
            ObjectNode action = blocks.addObject();
            action.put("type", "动作描述");
            action.put("text", scene.summary());

            if (!scene.characters().isEmpty()) {
                ObjectNode dialogue = blocks.addObject();
                dialogue.put("type", "对白");
                dialogue.put("speakerId", scene.characters().getFirst());
                dialogue.put("text", "我们不能停下。");
            }
        } else {
            for (int i = 0; i < blocks.size(); i++) {
                JsonNode item = blocks.get(i);
                if (item instanceof ObjectNode blockNode) {
                    StageJsonSupport.putIfBlank(blockNode, "type", "动作描述");
                    StageJsonSupport.putIfBlank(blockNode, "text", scene.summary());

                    if ("对白".equals(blockNode.path("type").asText())
                            && StageJsonSupport.isBlank(blockNode.path("speakerId").asText())
                            && !scene.characters().isEmpty()) {
                        blockNode.put("speakerId", scene.characters().getFirst());
                    }
                }
            }
        }
    }

    /**
     * 尽力把当前细粒度阶段写回任务表，供串行模式下的前端轮询展示使用。
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
     * 从场景规划、章节、角色、地点等数据构建分场写作上下文。
     *
     * @param scene 场景规划。
     * @param chaptersByIndex 章节索引映射。
     * @param charactersById 角色 ID 映射。
     * @param locationsById 地点 ID 映射。
     * @param continuityRules 连贯性规则列表。
     * @param previousSceneSummary 前一场摘要。
     * @return 分场写作上下文对象。
     */
    private static SceneDraftContext buildContext(PlannedScene scene,
                                                  Map<Integer, NovelChapter> chaptersByIndex,
                                                  Map<String, BibleCharacter> charactersById,
                                                  Map<String, BibleLocation> locationsById,
                                                  List<String> continuityRules,
                                                  @Nullable String previousSceneSummary) {
        List<NovelChapter> sourceChapters = scene.sourceChapters().stream()
                .map(chapterIndex -> requireChapter(chaptersByIndex, scene.id(), chapterIndex))
                .toList();
        List<BibleCharacter> characters = scene.characters().stream()
                .map(characterId -> requireCharacter(charactersById, scene.id(), characterId))
                .toList();
        BibleLocation location = requireLocation(locationsById, scene.id(), scene.locationId());
        return new SceneDraftContext(scene, sourceChapters, characters, location, continuityRules, previousSceneSummary);
    }

    /**
     * 校验分场草稿与上下文的一致性。
     */
    private static void validateDraft(SceneDraft draft, SceneDraftContext context) {
        Objects.requireNonNull(draft, "分场草稿不能为空");
        PlannedScene scene = context.plannedScene();
        if (!draft.id().equals(scene.id())) {
            throw new IllegalArgumentException("分场草稿 ID 必须与场景规划一致：" + draft.id());
        }
        if (!draft.sourceChapters().equals(scene.sourceChapters())) {
            throw new IllegalArgumentException("分场草稿的源章节必须与场景规划一致：" + scene.id());
        }
        if (!draft.locationId().equals(scene.locationId())) {
            throw new IllegalArgumentException("分场草稿的地点 ID 必须与场景规划一致：" + scene.id());
        }
        if (!draft.characters().equals(scene.characters())) {
            throw new IllegalArgumentException("分场草稿的角色列表必须与场景规划一致：" + scene.id());
        }
        validateBlockSpeakers(draft, context);
    }

    /**
     * 校验分场草稿中对白块的发言者 ID 是否有效。
     */
    private static void validateBlockSpeakers(SceneDraft draft, SceneDraftContext context) {
        Set<String> availableCharacterIds = new HashSet<>();
        context.characters().forEach(character -> availableCharacterIds.add(character.id()));
        for (DraftSceneBlock block : draft.blocks()) {
            if (block.speakerId() != null && !availableCharacterIds.contains(block.speakerId())) {
                throw new IllegalArgumentException("分场草稿中对白块的发言者必须存在于上下文中："
                        + draft.id() + " -> " + block.speakerId());
            }
        }
    }

    /**
     * 将章节列表索引为按章节号映射的 Map。
     */
    private static Map<Integer, NovelChapter> indexChapters(List<NovelChapter> chapters) {
        if (chapters == null || chapters.isEmpty()) {
            throw new IllegalArgumentException("章节列表不能为空");
        }
        Map<Integer, NovelChapter> chaptersByIndex = new HashMap<>();
        for (NovelChapter chapter : chapters) {
            Objects.requireNonNull(chapter, "章节列表中不能包含 null 元素");
            NovelChapter previous = chaptersByIndex.putIfAbsent(chapter.getChapterIndex(), chapter);
            if (previous != null) {
                throw new IllegalArgumentException("章节号必须唯一：" + chapter.getChapterIndex());
            }
        }
        return Map.copyOf(chaptersByIndex);
    }

    /**
     * 将角色列表索引为按角色 ID 映射的 Map。
     */
    private static Map<String, BibleCharacter> indexCharacters(List<BibleCharacter> characters) {
        Map<String, BibleCharacter> charactersById = new HashMap<>();
        for (BibleCharacter character : characters) {
            BibleCharacter previous = charactersById.putIfAbsent(character.id(), character);
            if (previous != null) {
                throw new IllegalArgumentException("故事圣经中的角色 ID 必须唯一：" + character.id());
            }
        }
        return Map.copyOf(charactersById);
    }

    /**
     * 将地点列表索引为按地点 ID 映射的 Map。
     */
    private static Map<String, BibleLocation> indexLocations(List<BibleLocation> locations) {
        Map<String, BibleLocation> locationsById = new HashMap<>();
        for (BibleLocation location : locations) {
            BibleLocation previous = locationsById.putIfAbsent(location.id(), location);
            if (previous != null) {
                throw new IllegalArgumentException("故事圣经中的地点 ID 必须唯一：" + location.id());
            }
        }
        return Map.copyOf(locationsById);
    }

    /**
     * 校验场景规划中的场景 ID 唯一性。
     */
    private static void validateSceneIds(ScenePlan scenePlan) {
        Set<String> sceneIds = new HashSet<>();
        for (PlannedScene scene : scenePlan.scenes()) {
            if (!sceneIds.add(scene.id())) {
                throw new IllegalArgumentException("场景 ID 必须唯一：" + scene.id());
            }
        }
    }

    /**
     * 根据章节索引获取章节对象，若不存在则抛出异常。
     */
    private static NovelChapter requireChapter(Map<Integer, NovelChapter> chaptersByIndex, String sceneId,
                                               Integer chapterIndex) {
        NovelChapter chapter = chaptersByIndex.get(chapterIndex);
        if (chapter == null) {
            throw new IllegalArgumentException("场景引用的章节必须存在于章节列表中："
                    + sceneId + " -> " + chapterIndex);
        }
        return chapter;
    }

    /**
     * 根据角色 ID 获取角色对象，若不存在则抛出异常。
     */
    private static BibleCharacter requireCharacter(Map<String, BibleCharacter> charactersById, String sceneId,
                                                   String characterId) {
        BibleCharacter character = charactersById.get(characterId);
        if (character == null) {
            throw new IllegalArgumentException("场景引用的角色必须存在于故事圣经中："
                    + sceneId + " -> " + characterId);
        }
        return character;
    }

    /**
     * 根据地点 ID 获取地点对象，若不存在则抛出异常。
     */
    private static BibleLocation requireLocation(Map<String, BibleLocation> locationsById, String sceneId,
                                                 String locationId) {
        BibleLocation location = locationsById.get(locationId);
        if (location == null) {
            throw new IllegalArgumentException("场景引用的地点必须存在于故事圣经中："
                    + sceneId + " -> " + locationId);
        }
        return location;
    }

    /**
     * 场景任务记录，用于并行生成时携带场景和前一场景摘要。
     */
    private record SceneTask(PlannedScene scene, String previousSceneSummary) {
    }

    /**
     * 分场草稿阶段的输入快照。
     *
     * <p>该快照只用于计算 inputHash。只要项目、分场写作上下文或生成参数变化，
     * 就会得到不同哈希，从而避免复用过期的分场草稿。</p>
     *
     * @param projectId 项目 ID
     * @param projectTitle 项目标题
     * @param plannedScene 场景规划
     * @param sourceChapters 源章节列表
     * @param characters 角色列表
     * @param location 地点
     * @param continuityRules 连贯性规则
     * @param previousSceneSummary 前一场景摘要
     * @param format 输出格式
     * @param tone 语气风格
     * @param dialogueDensity 对话密度
     * @param narrationRetention 叙述保留度
     * @param hasAdditionalInstructions 是否有额外指令
     * @param additionalInstructions 额外指令内容
     */
    private record SceneDraftInput(
            Long projectId,
            String projectTitle,
            PlannedScene plannedScene,
            List<SourceChapterInput> sourceChapters,
            List<BibleCharacter> characters,
            BibleLocation location,
            List<String> continuityRules,
            String previousSceneSummary,
            String format,
            String tone,
            int dialogueDensity,
            int narrationRetention,
            boolean hasAdditionalInstructions,
            String additionalInstructions
    ) {
        /**
         * 从项目、分场写作上下文和生成选项构建输入快照。
         *
         * @param project 小说改编项目。
         * @param context 分场写作上下文。
         * @param options 生成选项。
         * @return 输入快照对象。
         */
        private static SceneDraftInput from(NovelProject project, SceneDraftContext context, GenerationOptions options) {
            return new SceneDraftInput(
                    project.getId(),
                    project.getTitle(),
                    context.plannedScene(),
                    context.sourceChapters().stream()
                            .map(SourceChapterInput::from)
                            .toList(),
                    context.characters(),
                    context.location(),
                    context.continuityRules(),
                    context.previousSceneSummary(),
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
     * 源章节输入快照，用于计算输入哈希。
     *
     * @param chapterId 章节 ID
     * @param chapterIndex 章节索引
     * @param title 章节标题
     * @param content 章节内容
     */
    private record SourceChapterInput(
            Long chapterId,
            int chapterIndex,
            String title,
            String content
    ) {
        /**
         * 从章节对象构建源章节输入快照。
         *
         * @param chapter 章节对象。
         * @return 源章节输入快照。
         */
        private static SourceChapterInput from(NovelChapter chapter) {
            return new SourceChapterInput(
                    chapter.getId(),
                    chapter.getChapterIndex(),
                    chapter.getTitle(),
                    chapter.getContent()
            );
        }
    }
}