package com.novelplayer.application.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelplayer.ai.LlmJsonClient;
import com.novelplayer.application.generation.model.BibleCharacter;
import com.novelplayer.application.generation.model.BibleLocation;
import com.novelplayer.application.generation.model.ChapterDigest;
import com.novelplayer.application.generation.model.CharacterMention;
import com.novelplayer.application.generation.model.LocationMention;
import com.novelplayer.application.generation.model.StoryBible;
import com.novelplayer.application.generation.prompt.StoryBiblePromptBuilder;
import com.novelplayer.domain.generation.GenerationJob;
import com.novelplayer.domain.project.NovelProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 故事圣经阶段生成器（基于 LLM JSON 客户端）。
 *
 * <p>该实现通过 {@link LlmJsonClient} 直接与大语言模型交互，生成器自身负责提示词构建、
 * JSON 响应解析和数据规范化。故事圣经依赖全部章节摘要作为输入，生成全局统一的
 * 角色、场景、主线剧情和连贯性规则。</p>
 *
 *  * 负责自己本阶段的：
 *  * 组装阶段输入
 *  * 构造 prompt
 *  * 调 LlmJsonClient.requestJson(...)
 *  * 自己做 JSON 映射、normalize、validate
 *
 * <p>故事圣经是后续场景规划和分场写作的核心依据，必须保证角色 ID 和地点 ID 的
 * 唯一性和格式一致性。</p>
 *
 * @see LlmJsonClient
 * @see StoryBible
 */
@Service
@ConditionalOnBean(LlmJsonClient.class)
@ConditionalOnProperty(prefix = "novel-player.generation", name = "pipeline-mode", havingValue = "staged",
        matchIfMissing = true)
public class StoryBibleGenerator {

    private static final Logger log = LoggerFactory.getLogger(StoryBibleGenerator.class);

    /**
     * 角色 ID 格式校验正则表达式：char_001 ~ char_999
     */
    private static final Pattern CHARACTER_ID_PATTERN = Pattern.compile("char_\\d{3}");

    /**
     * 地点 ID 格式校验正则表达式：loc_001 ~ loc_999
     */
    private static final Pattern LOCATION_ID_PATTERN = Pattern.compile("loc_\\d{3}");

    private final StoryBiblePromptBuilder promptBuilder;
    private final LlmJsonClient llmJsonClient;
    private final ObjectMapper objectMapper;
    private final GenerationStageStore stageStore;

    /**
     * 创建故事圣经阶段生成器。
     *
     * @param llmJsonClient LLM JSON 客户端，负责与大语言模型通信。
     * @param objectMapper JSON 序列化/反序列化工具。
     * @param stageStore 生成阶段结果存取层。
     */
    public StoryBibleGenerator(LlmJsonClient llmJsonClient,
                               ObjectMapper objectMapper,
                               GenerationStageStore stageStore,
                               StoryBiblePromptBuilder promptBuilder) {
        this.llmJsonClient = Objects.requireNonNull(llmJsonClient, "llmJsonClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.stageStore = Objects.requireNonNull(stageStore, "stageStore must not be null");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder must not be null");
    }

    /**
     * 生成或复用故事圣经。
     *
     * <p>故事圣经是对整个项目的全局建模，包括角色列表、场景列表、主线剧情、
     * 主题和连贯性规则。该方法会先检查是否已存在相同输入的缓存结果，
     * 若存在则直接复用，否则调用大语言模型生成新的故事圣经并持久化。</p>
     *
     * @param job 当前生成任务，必须已经持久化。
     * @param project 小说改编项目。
     * @param chapterDigests 按章节顺序排列的章节摘要列表。
     * @param options 生成参数。
     * @return 故事圣经对象。
     */
    public StoryBible generate(GenerationJob job, NovelProject project, List<ChapterDigest> chapterDigests,
                               GenerationOptions options) {
        Objects.requireNonNull(job, "job must not be null");
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(options, "options must not be null");
        List<ChapterDigest> digests = requireChapterDigests(chapterDigests);

        String stageName = GenerationStageNames.STORY_BIBLE;
        String inputHash = stageStore.sha256OfJson(StoryBibleInput.from(project, digests, options));
        log.info("开始生成故事圣经 jobId={} projectId={} digestCount={} stageName={} inputHash={}",
                job.getId(), project.getId(), digests.size(), stageName, inputHash);

        Optional<StoryBible> cached = stageStore.findSucceeded(job, stageName, inputHash, StoryBible.class);
        if (cached.isPresent()) {
            StoryBible bible = cached.orElseThrow();
            validate(bible);
            log.info("复用已存在的故事圣经 jobId={} projectId={} characterCount={} locationCount={}",
                    job.getId(), project.getId(), bible.characters().size(), bible.locations().size());
            return bible;
        }

        try {
            StoryBible bible = requestStoryBible(project, digests, options);
            validate(bible);
            stageStore.saveSucceeded(job, stageName, inputHash, bible);
            log.info("故事圣经生成并保存成功 jobId={} projectId={} characterCount={} locationCount={} ruleCount={}",
                    job.getId(), project.getId(), bible.characters().size(), bible.locations().size(),
                    bible.continuityRules().size());
            return bible;
        } catch (RuntimeException exception) {
            stageStore.saveFailed(job, stageName, inputHash, exception.getMessage());
            log.warn("故事圣经生成失败 jobId={} projectId={} stageName={} error={}",
                    job.getId(), project.getId(), stageName, exception.getMessage(), exception);
            throw exception;
        }
    }

    /**
     * 调用大语言模型生成故事圣经。
     *
     * <p>该方法构建提示词调用 LLM JSON 客户端，获取 JSON 响应后进行数据规范化，
     * 最终反序列化为 {@link StoryBible} 对象。</p>
     *
     * @param project 小说改编项目。
     * @param chapterDigests 章节摘要列表。
     * @param options 生成参数。
     * @return 故事圣经对象。
     */
    private StoryBible requestStoryBible(NovelProject project, List<ChapterDigest> chapterDigests,
                                         GenerationOptions options) {
        String inputJson = StageJsonSupport.toPrettyJson(
                objectMapper,
                StoryBibleInput.from(project, chapterDigests, options)
        );

        StoryBiblePromptBuilder.PromptMessages prompt = promptBuilder.build(inputJson);

        String json = llmJsonClient.requestJson(
                GenerationStageNames.STORY_BIBLE,
                prompt.systemPrompt(),
                prompt.userPrompt()
        );

        ObjectNode root = StageJsonSupport.readObject(objectMapper, GenerationStageNames.STORY_BIBLE, json);
        normalizeStoryBible(root, chapterDigests);
        return StageJsonSupport.treeToValue(objectMapper, "故事圣经", root, StoryBible.class);
    }

    /**
     * 规范化 AI 返回的故事圣经 JSON 数据。
     *
     * <p>该方法对 AI 输出进行以下处理：
     * <ul>
     *   <li>确保必填字段存在且有默认值</li>
     *   <li>若角色列表为空，从章节摘要中提取角色名称合成</li>
     *   <li>若场景列表为空，从章节摘要中提取场景名称合成</li>
     *   <li>确保角色和场景的 ID 格式正确、名称不为空</li>
     * </ul>
     * </p>
     *
     * @param root 待规范化的 JSON 根节点。
     * @param chapterDigests 章节摘要列表，用于合成默认数据。
     */
    private void normalizeStoryBible(ObjectNode root, List<ChapterDigest> chapterDigests) {
        StageJsonSupport.putIfBlank(root, "mainPlot", buildMainPlotFallback(chapterDigests));
        StageJsonSupport.ensureArray(objectMapper, root, "themes");
        StageJsonSupport.ensureArray(objectMapper, root, "continuityRules");

        ArrayNode charactersNode = StageJsonSupport.ensureArray(objectMapper, root, "characters");
        if (charactersNode.isEmpty()) {
            synthesizeCharacters(charactersNode, chapterDigests);
        } else {
            for (int i = 0; i < charactersNode.size(); i++) {
                JsonNode item = charactersNode.get(i);
                if (item instanceof ObjectNode characterNode) {
                    StageJsonSupport.putIfBlank(characterNode, "id", "char_%03d".formatted(i + 1));
                    StageJsonSupport.putIfBlank(characterNode, "name", "角色 " + (i + 1));
                    StageJsonSupport.putIfBlank(characterNode, "role", i == 0 ? "protagonist" : "supporting");
                    StageJsonSupport.ensureArray(objectMapper, characterNode, "aliases");
                    StageJsonSupport.ensureArray(objectMapper, characterNode, "traits");
                }
            }
        }

        ArrayNode locationsNode = StageJsonSupport.ensureArray(objectMapper, root, "locations");
        if (locationsNode.isEmpty()) {
            synthesizeLocations(locationsNode, chapterDigests);
        } else {
            for (int i = 0; i < locationsNode.size(); i++) {
                JsonNode item = locationsNode.get(i);
                if (item instanceof ObjectNode locationNode) {
                    StageJsonSupport.putIfBlank(locationNode, "id", "loc_%03d".formatted(i + 1));
                    StageJsonSupport.putIfBlank(locationNode, "name", "场景 " + (i + 1));
                    StageJsonSupport.putIfBlank(locationNode, "type", "室内");
                }
            }
        }
    }

    /**
     * 从章节摘要中提取角色名称，合成角色列表。
     *
     * <p>该方法遍历所有章节摘要，收集所有提到的角色名称，去重后生成
     * 带有稳定 ID 的角色对象。第一个角色被标记为主角（protagonist）。</p>
     *
     * @param charactersNode 待填充的角色数组节点。
     * @param chapterDigests 章节摘要列表。
     */
    private void synthesizeCharacters(ArrayNode charactersNode, List<ChapterDigest> chapterDigests) {
        Set<String> names = new LinkedHashSet<>();
        for (ChapterDigest digest : chapterDigests) {
            for (CharacterMention mention : digest.characters()) {
                names.add(mention.name());
            }
        }
        if (names.isEmpty()) {
            names.add("主角");
        }

        int index = 1;
        for (String name : names) {
            ObjectNode character = charactersNode.addObject();
            character.put("id", "char_%03d".formatted(index));
            character.put("name", name);
            character.putArray("aliases");
            character.put("role", index == 1 ? "protagonist" : "supporting");
            character.put("goal", index == 1 ? "推动主线真相浮出水面" : "支撑或阻碍当前冲突");
            character.putArray("traits");
            index++;
        }
    }

    /**
     * 从章节摘要中提取场景名称，合成场景列表。
     *
     * <p>该方法遍历所有章节摘要，收集所有提到的场景名称，去重后生成
     * 带有稳定 ID 的场景对象。所有场景默认类型为室内（interior）。</p>
     *
     * @param locationsNode 待填充的场景数组节点。
     * @param chapterDigests 章节摘要列表。
     */
    private void synthesizeLocations(ArrayNode locationsNode, List<ChapterDigest> chapterDigests) {
        Set<String> names = new LinkedHashSet<>();
        for (ChapterDigest digest : chapterDigests) {
            for (LocationMention mention : digest.locations()) {
                names.add(mention.name());
            }
        }
        if (names.isEmpty()) {
            names.add("主要场景");
        }

        int index = 1;
        for (String name : names) {
            ObjectNode location = locationsNode.addObject();
            location.put("id", "loc_%03d".formatted(index));
            location.put("name", name);
            location.put("type", "室内");
            index++;
        }
    }

    /**
     * 当 AI 未返回主线剧情时的回退值。
     *
     * <p>从章节摘要中提取第一个有效的摘要内容作为主线剧情回退值。</p>
     *
     * @param chapterDigests 章节摘要列表。
     * @return 回退的主线剧情文本。
     */
    private String buildMainPlotFallback(List<ChapterDigest> chapterDigests) {
        String joined = chapterDigests.stream()
                .map(ChapterDigest::summary)
                .filter(summary -> summary != null && !summary.isBlank())
                .findFirst()
                .orElse("主角不断靠近核心真相，并被迫面对越来越强的阻力。");
        return StageJsonSupport.summarize(joined, 160);
    }

    /**
     * 校验故事圣经的数据完整性。
     *
     * <p>校验内容包括：
     * <ul>
     *   <li>角色 ID 格式必须匹配 char_001 格式</li>
     *   <li>角色 ID 必须唯一</li>
     *   <li>角色名称不能为空</li>
     *   <li>地点 ID 格式必须匹配 loc_001 格式</li>
     *   <li>地点 ID 必须唯一</li>
     *   <li>地点名称不能为空</li>
     * </ul>
     * </p>
     *
     * @param bible 待校验的故事圣经对象。
     * @throws IllegalArgumentException 当数据不符合规范时抛出。
     */
    private static void validate(StoryBible bible) {
        Objects.requireNonNull(bible, "故事圣经不能为空");
        validateCharacterIds(bible.characters());
        validateLocationIds(bible.locations());
    }

    /**
     * 校验角色列表的 ID 格式和唯一性。
     *
     * @param characters 角色列表。
     * @throws IllegalArgumentException 当 ID 格式不正确或不唯一时抛出。
     */
    private static void validateCharacterIds(List<BibleCharacter> characters) {
        Set<String> ids = new HashSet<>();
        for (BibleCharacter character : characters) {
            if (!CHARACTER_ID_PATTERN.matcher(character.id()).matches()) {
                throw new IllegalArgumentException("角色 ID 必须符合 char_001 格式：" + character.id());
            }
            if (!ids.add(character.id())) {
                throw new IllegalArgumentException("角色 ID 必须唯一：" + character.id());
            }
            if (character.name().isBlank()) {
                throw new IllegalArgumentException("角色名称不能为空：" + character.id());
            }
        }
    }

    /**
     * 校验场景列表的 ID 格式和唯一性。
     *
     * @param locations 场景列表。
     * @throws IllegalArgumentException 当 ID 格式不正确或不唯一时抛出。
     */
    private static void validateLocationIds(List<BibleLocation> locations) {
        Set<String> ids = new HashSet<>();
        for (BibleLocation location : locations) {
            if (!LOCATION_ID_PATTERN.matcher(location.id()).matches()) {
                throw new IllegalArgumentException("场景 ID 必须符合 loc_001 格式：" + location.id());
            }
            if (!ids.add(location.id())) {
                throw new IllegalArgumentException("场景 ID 必须唯一：" + location.id());
            }
            if (location.name().isBlank()) {
                throw new IllegalArgumentException("场景名称不能为空：" + location.id());
            }
        }
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
     * 故事圣经阶段的输入快照。
     *
     * <p>该快照只用于计算 inputHash。只要项目、章节摘要列表或生成参数变化，
     * 就会得到不同哈希，从而避免复用过期的故事圣经。</p>
     *
     * @param projectId 项目 ID
     * @param projectTitle 项目标题
     * @param chapterDigests 章节摘要列表
     * @param format 输出格式
     * @param tone 语气风格
     * @param dialogueDensity 对话密度
     * @param narrationRetention 叙述保留度
     * @param hasAdditionalInstructions 是否有额外指令
     * @param additionalInstructions 额外指令内容
     */
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
        /**
         * 从项目、章节摘要列表和生成选项构建输入快照。
         *
         * @param project 小说改编项目。
         * @param chapterDigests 章节摘要列表。
         * @param options 生成选项。
         * @return 输入快照对象。
         */
        private static StoryBibleInput from(NovelProject project, List<ChapterDigest> chapterDigests,
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
    }
}