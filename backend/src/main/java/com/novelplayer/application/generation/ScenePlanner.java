package com.novelplayer.application.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelplayer.ai.LlmJsonClient;
import com.novelplayer.application.generation.model.BibleCharacter;
import com.novelplayer.application.generation.model.BibleLocation;
import com.novelplayer.application.generation.model.ChapterDigest;
import com.novelplayer.application.generation.model.PlannedScene;
import com.novelplayer.application.generation.model.ScenePlan;
import com.novelplayer.application.generation.model.StoryBible;
import com.novelplayer.application.generation.prompt.ScenePlanPromptBuilder;
import com.novelplayer.domain.generation.GenerationJob;
import com.novelplayer.domain.project.NovelProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 场景规划阶段生成器（基于 LLM JSON 客户端）。
 *
 * <p>该实现通过 {@link LlmJsonClient} 直接与大语言模型交互，生成器自身负责提示词构建、JSON 响应解析和数据规范化。场景规划将多个章节的摘要内容重新组织为可表演的场景列表，每个场景可能覆盖一个或多个章节的核心冲突。</p>
 *
 *  * 负责自己本阶段的：
 *  * 组装阶段输入
 *  * 构造 prompt
 *  * 调 LlmJsonClient.requestJson(...)
 *  * 自己做 JSON 映射、normalize、validate
 *
 * <p>场景规划依赖全部章节摘要和故事圣经作为输入，生成的场景 ID、角色 ID 和地点 ID
 * 必须与故事圣经保持一致。</p>
 *
 * @see LlmJsonClient
 * @see ScenePlan
 * @see StoryBible
 */
@Service
@ConditionalOnBean(LlmJsonClient.class)
@ConditionalOnProperty(prefix = "novel-player.generation", name = "pipeline-mode", havingValue = "staged",
        matchIfMissing = true)
public class ScenePlanner {

    /**
     * 阶段化系统提示词，要求大语言模型只输出合法 JSON。
     * 同时要求角色 ID 和地点 ID 必须引用故事圣经中已存在的值。
     */
    private final ScenePlanPromptBuilder promptBuilder;
    private static final Logger log = LoggerFactory.getLogger(ScenePlanner.class);

    private final GenerationStageStore stageStore;

    private final LlmJsonClient llmJsonClient;
    private final ObjectMapper objectMapper;

    public ScenePlanner(LlmJsonClient llmJsonClient,
                        ObjectMapper objectMapper,
                        GenerationStageStore stageStore,
                        ScenePlanPromptBuilder promptBuilder) {
        this.llmJsonClient = Objects.requireNonNull(llmJsonClient, "llmJsonClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.stageStore = Objects.requireNonNull(stageStore, "stageStore must not be null");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder must not be null");
    }

    /**
     * 生成或复用场景规划。
     *
     * <p>场景规划是根据章节摘要和故事圣经，将叙事内容组织为可表演的场景列表。
     * 该方法会先检查是否已存在相同输入的缓存结果，若存在则直接复用，
     * 否则调用大语言模型生成新的场景规划并持久化。</p>
     *
     * @param job 当前生成任务，必须已经持久化。
     * @param project 小说改编项目。
     * @param chapterDigests 按章节顺序排列的章节摘要列表。
     * @param storyBible 故事圣经，包含全局角色和场景定义。
     * @param options 生成参数。
     * @return 场景规划对象。
     */
    public ScenePlan plan(GenerationJob job, NovelProject project, List<ChapterDigest> chapterDigests,
                          StoryBible storyBible, GenerationOptions options) {
        Objects.requireNonNull(job, "job must not be null");
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(storyBible, "storyBible must not be null");
        Objects.requireNonNull(options, "options must not be null");
        List<ChapterDigest> digests = requireChapterDigests(chapterDigests);

        String stageName = GenerationStageNames.SCENE_PLAN;
        String inputHash = stageStore.sha256OfJson(ScenePlanInput.from(project, digests, storyBible, options));
        log.info("开始生成场景规划 jobId={} projectId={} digestCount={} characterCount={} locationCount={} stageName={} inputHash={}",
                job.getId(), project.getId(), digests.size(), storyBible.characters().size(),
                storyBible.locations().size(), stageName, inputHash);

        Optional<ScenePlan> cached = stageStore.findSucceeded(job, stageName, inputHash, ScenePlan.class);
        if (cached.isPresent()) {
            ScenePlan scenePlan = cached.orElseThrow();
            validate(scenePlan, digests, storyBible);
            log.info("复用已存在的场景规划 jobId={} projectId={} sceneCount={}",
                    job.getId(), project.getId(), scenePlan.scenes().size());
            return scenePlan;
        }

        try {
            ScenePlan scenePlan = requestScenePlan(project, digests, storyBible, options);
            validate(scenePlan, digests, storyBible);
            stageStore.saveSucceeded(job, stageName, inputHash, scenePlan);
            log.info("场景规划生成并保存成功 jobId={} projectId={} sceneCount={} coveredChapterCount={}",
                    job.getId(), project.getId(), scenePlan.scenes().size(), collectChapterIndexes(digests).size());
            return scenePlan;
        } catch (RuntimeException exception) {
            stageStore.saveFailed(job, stageName, inputHash, exception.getMessage());
            log.warn("场景规划生成失败 jobId={} projectId={} stageName={} error={}",
                    job.getId(), project.getId(), stageName, exception.getMessage(), exception);
            throw exception;
        }
    }

    /**
     * 调用大语言模型生成场景规划。
     *
     * <p>该方法构建提示词调用 LLM JSON 客户端，获取 JSON 响应后进行数据规范化，
     * 最终反序列化为 {@link ScenePlan} 对象。</p>
     *
     * @param project 小说改编项目。
     * @param chapterDigests 章节摘要列表。
     * @param storyBible 故事圣经。
     * @param options 生成参数。
     * @return 场景规划对象。
     */
    private ScenePlan requestScenePlan(NovelProject project, List<ChapterDigest> chapterDigests,
                                       StoryBible storyBible, GenerationOptions options) {
        String inputJson = StageJsonSupport.toPrettyJson(
                objectMapper,
                ScenePlanInput.from(project, chapterDigests, storyBible, options)
        );

        ScenePlanPromptBuilder.PromptMessages prompt = promptBuilder.build(inputJson);

        String json = llmJsonClient.requestJson(
                GenerationStageNames.SCENE_PLAN,
                prompt.systemPrompt(),
                prompt.userPrompt()
        );

        ObjectNode root = StageJsonSupport.readObject(objectMapper, GenerationStageNames.SCENE_PLAN, json);
        normalizeScenePlan(root, chapterDigests, storyBible);
        return StageJsonSupport.treeToValue(objectMapper, "场景规划", root, ScenePlan.class);
    }

    /**
     * 规范化 AI 返回的场景规划 JSON 数据。

     * @param root 待规范化的 JSON 根节点。
     * @param chapterDigests 章节摘要列表，用于合成默认数据。
     * @param storyBible 故事圣经，用于获取角色和场景的默认值。
     */
    private void normalizeScenePlan(ObjectNode root, List<ChapterDigest> chapterDigests, StoryBible storyBible) {
        ArrayNode scenesNode = StageJsonSupport.ensureArray(objectMapper, root, "scenes");

        if (scenesNode.isEmpty()) {
            for (int i = 0; i < chapterDigests.size(); i++) {
                ChapterDigest digest = chapterDigests.get(i);
                ObjectNode sceneNode = scenesNode.addObject();
                sceneNode.put("id", "scene_%03d".formatted(i + 1));
                sceneNode.put("title", digest.title());
                ArrayNode sourceChapters = sceneNode.putArray("sourceChapters");
                sourceChapters.add(digest.chapterIndex());
                sceneNode.put("locationId", storyBible.locations().getFirst().id());
                sceneNode.put("timeOfDay", i == 0 ? "night" : "day");
                ArrayNode characters = sceneNode.putArray("characters");
                storyBible.characters().stream().limit(2).map(BibleCharacter::id).forEach(characters::add);
                sceneNode.put("dramaticPurpose", "推进改编主冲突。");
                sceneNode.put("summary", digest.summary());
                ArrayNode requiredBeats = sceneNode.putArray("requiredBeats");
                digest.majorEvents().forEach(requiredBeats::add);
                if (requiredBeats.isEmpty()) {
                    requiredBeats.add("保留该章最强的一处戏剧冲突。");
                }
            }
        } else {
            for (int i = 0; i < scenesNode.size(); i++) {
                ChapterDigest digest = chapterDigests.get(Math.min(i, chapterDigests.size() - 1));
                JsonNode item = scenesNode.get(i);
                if (item instanceof ObjectNode sceneNode) {
                    StageJsonSupport.putIfBlank(sceneNode, "id", "scene_%03d".formatted(i + 1));
                    StageJsonSupport.putIfBlank(sceneNode, "title", digest.title());

                    ArrayNode sourceChapters = StageJsonSupport.ensureArray(objectMapper, sceneNode, "sourceChapters");
                    if (sourceChapters.isEmpty()) {
                        sourceChapters.add(digest.chapterIndex());
                    }

                    StageJsonSupport.putIfBlank(sceneNode, "locationId", storyBible.locations().getFirst().id());
                    StageJsonSupport.putIfBlank(sceneNode, "timeOfDay", i == 0 ? "night" : "day");

                    ArrayNode characters = StageJsonSupport.ensureArray(objectMapper, sceneNode, "characters");
                    if (characters.isEmpty()) {
                        storyBible.characters().stream().limit(2).map(BibleCharacter::id).forEach(characters::add);
                    }

                    StageJsonSupport.putIfBlank(sceneNode, "dramaticPurpose", "推进改编主冲突。");
                    StageJsonSupport.putIfBlank(sceneNode, "summary", digest.summary());

                    ArrayNode requiredBeats = StageJsonSupport.ensureArray(objectMapper, sceneNode, "requiredBeats");
                    if (requiredBeats.isEmpty()) {
                        digest.majorEvents().forEach(requiredBeats::add);
                        if (requiredBeats.isEmpty()) {
                            requiredBeats.add("保留该章最强的一处戏剧冲突。");
                        }
                    }
                }
            }
        }
    }

    /**
     * 校验场景规划的数据完整性。
     *
     * @param scenePlan 待校验的场景规划对象。
     * @param chapterDigests 章节摘要列表，用于校验章节索引。
     * @param storyBible 故事圣经，用于校验角色和场景 ID。
     * @throws IllegalArgumentException 当数据不符合规范时抛出。
     */
    private static void validate(ScenePlan scenePlan, List<ChapterDigest> chapterDigests, StoryBible storyBible) {
        Objects.requireNonNull(scenePlan, "场景规划不能为空");
        // set 可以去重，list有 id 顺序
        Set<Integer> availableChapterIndexes = collectChapterIndexes(chapterDigests);
        Set<String> availableCharacterIds = collectCharacterIds(storyBible);
        Set<String> availableLocationIds = collectLocationIds(storyBible);
        Set<String> sceneIds = new HashSet<>();

        for (PlannedScene scene : scenePlan.scenes()) {
            validateSceneId(scene, sceneIds);
            validateSourceChapters(scene, availableChapterIndexes);
            validateLocation(scene, availableLocationIds);
            validateCharacters(scene, availableCharacterIds);
        }
    }

    /**
     * 校验场景编号不重复。
     *
     * @param scene 待校验的场景。
     * @param sceneIds 已收集的场景 ID 集合。
     * @throws IllegalArgumentException 当场景 ID 重复时抛出。
     */
    private static void validateSceneId(PlannedScene scene, Set<String> sceneIds) {
        if (!sceneIds.add(scene.id())) {
            throw new IllegalArgumentException("场景 ID 必须唯一：" + scene.id());
        }
    }

    /**
     * 校验场景引用的章节索引是否存在于章节摘要中。
     *
     * @param scene 待校验的场景。
     * @param availableChapterIndexes 可用的章节索引集合。
     * @throws IllegalArgumentException 当章节索引不存在时抛出。
     */
    private static void validateSourceChapters(PlannedScene scene, Set<Integer> availableChapterIndexes) {
        for (Integer chapterIndex : scene.sourceChapters()) {
            if (!availableChapterIndexes.contains(chapterIndex)) {
                throw new IllegalArgumentException("场景引用的章节必须存在于章节摘要中："
                        + scene.id() + " -> " + chapterIndex);
            }
        }
    }

    /**
     * 校验场景引用的地点存在于故事圣经地点表。
     *
     * @param scene 待校验的场景。
     * @param availableLocationIds 可用的地点 ID 集合。
     * @throws IllegalArgumentException 当地点 ID 不存在时抛出。
     */
    private static void validateLocation(PlannedScene scene, Set<String> availableLocationIds) {
        if (!availableLocationIds.contains(scene.locationId())) {
            throw new IllegalArgumentException("场景引用的地点必须存在于故事圣经中："
                    + scene.id() + " -> " + scene.locationId());
        }
    }

    /**
     * 校验场景出场人物都存在于故事圣经人物表。
     *
     * @param scene 待校验的场景。
     * @param availableCharacterIds 可用的角色 ID 集合。
     * @throws IllegalArgumentException 当角色 ID 不存在时抛出。
     */
    private static void validateCharacters(PlannedScene scene, Set<String> availableCharacterIds) {
        for (String characterId : scene.characters()) {
            if (!availableCharacterIds.contains(characterId)) {
                throw new IllegalArgumentException("场景引用的角色必须存在于故事圣经中："
                        + scene.id() + " -> " + characterId);
            }
        }
    }

    /**
     * 收集章节摘要中的所有章节索引，并校验唯一性。
     *
     * @param chapterDigests 章节摘要列表。
     * @return 章节索引的不可变集合。
     * @throws IllegalArgumentException 当章节索引重复时抛出。
     */
    private static Set<Integer> collectChapterIndexes(List<ChapterDigest> chapterDigests) {
        Set<Integer> chapterIndexes = new HashSet<>();
        for (ChapterDigest digest : chapterDigests) {
            if (!chapterIndexes.add(digest.chapterIndex())) {
                throw new IllegalArgumentException("章节摘要的索引必须唯一：" + digest.chapterIndex());
            }
        }
        return chapterIndexes;
    }

    /**
     * 收集故事圣经中的所有角色 ID，并校验唯一性。
     *
     * @param storyBible 故事圣经。
     * @return 角色 ID 的不可变集合。
     * @throws IllegalArgumentException 当角色 ID 重复时抛出。
     */
    private static Set<String> collectCharacterIds(StoryBible storyBible) {
        Set<String> characterIds = new HashSet<>();
        for (BibleCharacter character : storyBible.characters()) {
            if (!characterIds.add(character.id())) {
                throw new IllegalArgumentException("故事圣经中的角色 ID 必须唯一：" + character.id());
            }
        }
        return characterIds;
    }

    /**
     * 收集故事圣经中的所有地点 ID，并校验唯一性。
     *
     * @param storyBible 故事圣经。
     * @return 地点 ID 的不可变集合。
     * @throws IllegalArgumentException 当地点 ID 重复时抛出。
     */
    private static Set<String> collectLocationIds(StoryBible storyBible) {
        Set<String> locationIds = new HashSet<>();
        for (BibleLocation location : storyBible.locations()) {
            if (!locationIds.add(location.id())) {
                throw new IllegalArgumentException("故事圣经中的地点 ID 必须唯一：" + location.id());
            }
        }
        return locationIds;
    }

    /**
     * 校验并复制章节摘要列表，确保非空且不包含 null 元素。
     *
     * @param chapterDigests 待校验的章节摘要列表。
     * @return 不可变的章节摘要列表副本。
     * @throws IllegalArgumentException 当列表为空或包含 null 元素时抛出。
     */
    private static List<ChapterDigest> requireChapterDigests(List<ChapterDigest> chapterDigests) {
        if (chapterDigests == null || chapterDigests.isEmpty()) {
            throw new IllegalArgumentException("章节摘要列表不能为空");
        }
        return List.copyOf(chapterDigests.stream()
                .map(digest -> Objects.requireNonNull(digest, "章节摘要列表中不能包含 null 元素"))
                .toList());
    }

    /**
     * 场景规划阶段的输入快照。
     *
     * <p>该快照只用于计算 inputHash。只要项目、章节摘要列表、故事圣经或生成参数变化，
     * 就会得到不同哈希，从而避免复用过期的场景规划。</p>
     *
     * @param projectId 项目 ID
     * @param projectTitle 项目标题
     * @param chapterDigests 章节摘要列表
     * @param storyBible 故事圣经
     * @param format 输出格式
     * @param tone 语气风格
     * @param dialogueDensity 对话密度
     * @param narrationRetention 叙述保留度
     * @param hasAdditionalInstructions 是否有额外指令
     * @param additionalInstructions 额外指令内容
     */
    private record ScenePlanInput(
            Long projectId,
            String projectTitle,
            List<ChapterDigest> chapterDigests,
            StoryBible storyBible,
            String format,
            String tone,
            int dialogueDensity,
            int narrationRetention,
            boolean hasAdditionalInstructions,
            String additionalInstructions
    ) {
        /**
         * 从项目、章节摘要列表、故事圣经和生成选项构建输入快照。
         *
         * @param project 小说改编项目。
         * @param chapterDigests 章节摘要列表。
         * @param storyBible 故事圣经。
         * @param options 生成选项。
         * @return 输入快照对象。
         */
        private static ScenePlanInput from(NovelProject project, List<ChapterDigest> chapterDigests,
                                           StoryBible storyBible, GenerationOptions options) {
            return new ScenePlanInput(
                    project.getId(),
                    project.getTitle(),
                    chapterDigests,
                    storyBible,
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