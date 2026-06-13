package com.novelplayer.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelplayer.application.generation.GenerationOptions;
import com.novelplayer.application.generation.model.BibleCharacter;
import com.novelplayer.application.generation.model.BibleLocation;
import com.novelplayer.application.generation.model.ChapterDigest;
import com.novelplayer.application.generation.model.CharacterMention;
import com.novelplayer.application.generation.model.DraftSceneBlock;
import com.novelplayer.application.generation.model.LocationMention;
import com.novelplayer.application.generation.model.PlannedScene;
import com.novelplayer.application.generation.model.SceneDraft;
import com.novelplayer.application.generation.model.SceneDraftContext;
import com.novelplayer.application.generation.model.ScenePlan;
import com.novelplayer.application.generation.model.StoryBible;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * staged 生成链路在 mock-ai=false 时的正式 AI 实现。
 *
 * <p>关闭 mock 后，如果模型输出某个必填字段为空，Jackson 在反序列化 record 时会直接失败。
 * 这里先把模型文本收敛成 JSON，再按阶段用输入上下文补齐关键字段，降低 staged 链路整体失败率。</p>
 *
 *   统一调用 LlmJsonClient 获取稳定 JSON 响应
 *   对每个阶段的结果进行 normalize 标准化处理，补全缺失的必要字段
 *   使用输入上下文（如原章节内容、StoryBible）作为 fallback 数据源

 */
@Component
@ConditionalOnProperty(prefix = "novel-player.generation", name = "mock-ai", havingValue = "false")
public class DeepSeekStagedScriptAiClient implements StagedScriptAiClient {

    private static final String STAGED_SYSTEM_PROMPT = """
        你是规范化小说改编助手。
        仅输出JSON格式内容。
        禁止输出Markdown代码块、解释文字或多余叙述文本。
        所有必填字段必须存在且不能为空值。
        只能使用输入上下文里已存在的ID与关联引用。
        """;

    private final ObjectMapper objectMapper;
    private final LlmJsonClient llmJsonClient;

    public DeepSeekStagedScriptAiClient(ObjectMapper objectMapper, LlmJsonClient llmJsonClient) {
        this.objectMapper = objectMapper;
        this.llmJsonClient = llmJsonClient;
    }

    /**
     * 生成章节摘要（第1阶段）
     *
     * @param project  项目信息
     * @param chapter  原始章节
     * @param options  生成选项
     * @return 标准化的章节摘要
     */
    @Override
    public ChapterDigest generateChapterDigest(NovelProject project, NovelChapter chapter, GenerationOptions options) {
        String userPrompt = """
            任务：根据单篇小说章节生成一份章节摘要（ChapterDigest）
            输出规范：
            - 仅返回单个JSON对象
            - chapterIndex数值必须和入参的章节序号保持一致
            - title（章节标题）与summary（章节概述）内容不能为空
            - majorEvents（关键事件）、characters（出场人物）、locations（场景地点）、conflicts（矛盾冲突）、openThreads（遗留悬念）、adaptationHints（改编提示）都必须为数组格式
            - 拿不准内容时，保留空数组，不可直接省略该字段
            输入内容：
            %s """.formatted(toJson(new ChapterDigestPromptInput(
                project.getTitle(),
                chapter.getChapterIndex(),
                chapter.getTitle(),
                chapter.getContent(),
                options
        )));

        return callChapterDigest(project, chapter, userPrompt);
    }

    /**
     * 生成故事圣经/设定集（第2阶段）
     *
     * @param project         项目信息
     * @param chapterDigests  已生成的章节摘要列表
     * @param options         生成选项
     * @return 标准化的故事圣经
     */
    @Override
    public StoryBible generateStoryBible(NovelProject project, List<ChapterDigest> chapterDigests,
                                         GenerationOptions options) {
        String userPrompt = """
                任务：依据各章节摘要生成一套小说设定集（故事圣经）（StoryBible）
                输出规范：
                - 仅返回单个JSON对象
                - characters（人物列表）、locations（地点列表）必须为非空数组
                - mainPlot（主线剧情）内容不能为空
                - 人物ID统一采用 char_001 这类格式
                - 地点ID统一采用 loc_001 这类格式
                输入内容：
                %s
                """.formatted(toJson(new StoryBiblePromptInput(
                project.getTitle(),
                chapterDigests,
                options
        )));

        return callStoryBible(project, chapterDigests, userPrompt);
    }

    /**
     * 生成场景规划（第3阶段）
     *
     * @param project         项目信息
     * @param chapterDigests  章节摘要列表
     * @param storyBible      故事圣经
     * @param options         生成选项
     * @return 标准化的场景规划
     */
    @Override
    public ScenePlan generateScenePlan(NovelProject project, List<ChapterDigest> chapterDigests, StoryBible storyBible,
                                       GenerationOptions options) {
        String userPrompt = """
                任务：结合章节摘要与小说设定集生成一份场景规划（ScenePlan）
                输出规范：
                - 仅返回单个JSON对象
                - scenes（场景列表）必须是非空数组
                - 每个场景内的id、title、locationId、timeOfDay、dramaticPurpose、summary字段均不能为空
                - 每个场景的sourceChapters（来源章节）、characters（登场人物）数组不可为空
                输入内容：
                %s
                """.formatted(toJson(new ScenePlanPromptInput(
                project.getTitle(),
                chapterDigests,
                storyBible,
                options
        )));

        return callScenePlan(project, chapterDigests, storyBible, userPrompt);
    }

    /**
     * 生成具体场景草稿（第4阶段）
     *
     * @param project  项目信息
     * @param context  场景草稿上下文（包含规划场景、来源章节、角色、地点等）
     * @param options  生成选项
     * @return 标准化的场景草稿
     */
    @Override
    public SceneDraft generateSceneDraft(NovelProject project, SceneDraftContext context, GenerationOptions options) {
        String userPrompt = """
            任务：根据场景草稿上下文生成一份场景草稿
            输出规范：
            - 仅返回一个JSON对象
            - id、sourceChapters、locationId、characters字段必须和入参plannedScene保持一致
            - title（标题）、dramaticPurpose（戏剧目的）、summary（内容摘要）不能为空
            - blocks为非空数组
            - 每个block必须包含type（类型）和text（文本）两个字段
            - 对话类型的block，speakerId必须使用入参characters里存在的角色ID
            输入内容：
            %s
            """.formatted(toJson(SceneDraftPromptInput.from(project, context, options)));

        return callSceneDraft(project, context, userPrompt);
    }

    // 核心调用方法（调用 LLM + 标准化）

    /**
     * 执行章节摘要生成，并对结果进行标准化修正
     */
    private ChapterDigest callChapterDigest(NovelProject project, NovelChapter chapter, String userPrompt) {
        String json = llmJsonClient.requestJson("chapter_digest", STAGED_SYSTEM_PROMPT, userPrompt);
        ObjectNode root = readObject("chapter_digest", json);

        normalizeChapterDigest(root, chapter);          // 补全/修正缺失字段
        return treeToValue("章节摘要", root, ChapterDigest.class);
    }

    /**
     * 执行故事圣经生成，并对结果进行标准化修正
     */
    private StoryBible callStoryBible(NovelProject project, List<ChapterDigest> chapterDigests, String userPrompt) {
        String json = llmJsonClient.requestJson("story_bible", STAGED_SYSTEM_PROMPT, userPrompt);
        ObjectNode root = readObject("story_bible", json);

        normalizeStoryBible(root, chapterDigests);      // 补全/修正缺失字段
        return treeToValue("故事圣经", root, StoryBible.class);
    }

    /**
     * 执行场景规划生成，并对结果进行标准化修正
     */
    private ScenePlan callScenePlan(NovelProject project, List<ChapterDigest> chapterDigests, StoryBible storyBible,
                                    String userPrompt) {
        String json = llmJsonClient.requestJson("scene_plan", STAGED_SYSTEM_PROMPT, userPrompt);
        ObjectNode root = readObject("scene_plan", json);

        normalizeScenePlan(root, chapterDigests, storyBible);  // 补全/修正缺失字段
        return treeToValue("场景规划", root, ScenePlan.class);
    }

    /**
     * 执行场景草稿生成，并对结果进行标准化修正
     */
    private SceneDraft callSceneDraft(NovelProject project, SceneDraftContext context, String userPrompt) {
        String json = llmJsonClient.requestJson("scene_draft", STAGED_SYSTEM_PROMPT, userPrompt);
        ObjectNode root = readObject("scene_draft", json);

        normalizeSceneDraft(root, context);             // 补全/修正缺失字段
        return treeToValue("分场草稿", root, SceneDraft.class);
    }

    // JSON 解析辅助方法
    /**
     * 将字符串解析为 ObjectNode，失败时抛出明确异常
     *
     * @param stageName 阶段名称（用于错误提示）
     * @param json      AI 返回的 JSON 字符串
     * @return 解析后的 ObjectNode
     * @throws IllegalStateException JSON 无效时抛出
     */
    private ObjectNode readObject(String stageName, String json) {
        try {
            return (ObjectNode) objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid JSON for stage: " + stageName, exception);
        }
    }

    /**
     * 将 ObjectNode 反序列化为目标类型
     *
     * @param stageName 阶段名称（用于错误提示）
     * @param root      JSON 节点
     * @param type      目标类型 Class
     * @param <T>       目标类型泛型
     * @return 反序列化后的对象
     * @throws IllegalStateException 反序列化失败时抛出
     */
    private <T> T treeToValue(String stageName, ObjectNode root, Class<T> type) {
        try {
            return objectMapper.treeToValue(root, type);
        } catch (Exception exception) {
            throw new IllegalStateException("DeepSeek 分段流式响应内容无效。" + stageName, exception);
        }
    }

    // 各阶段的标准化（Normalize）方法
    // 核心目的：当 AI 输出缺失某些必填字段时，用输入上下文中的合理值补全

    /**
     * 标准化章节摘要 JSON
     * - 补全标题、摘要
     * - 确保 characters 数组中的元素为对象格式（而非纯字符串）
     * - 确保 locations 数组中的元素为对象格式
     * - 为空的关键事件、冲突、悬念等数组提供默认值
     *
     * @param root    章节摘要 JSON 节点
     * @param chapter 原始章节（用于提取标题和内容作为 fallback）
     */
    private void normalizeChapterDigest(ObjectNode root, NovelChapter chapter) {
        // 补全顶层必填字段
        putIfBlank(root, "title", chapter.getTitle());
        putIfBlank(root, "summary", summarize(chapter.getContent(), 120));
        root.put("chapterIndex", chapter.getChapterIndex());

        // 处理 majorEvents 数组（要求元素为字符串）
        ArrayNode majorEvents = ensureArray(root, "majorEvents");
        normalizeStringArray(majorEvents, "event");
        if (majorEvents.isEmpty()) {
            majorEvents.add("概括本章的核心剧情转折点。");
        }

        // 处理 conflicts / openThreads / adaptationHints 数组
        ArrayNode conflicts = ensureArray(root, "conflicts");
        ArrayNode openThreads = ensureArray(root, "openThreads");
        ArrayNode adaptationHints = ensureArray(root, "adaptationHints");
        normalizeStringArray(conflicts, "conflict");
        normalizeStringArray(openThreads, "open thread");
        normalizeStringArray(adaptationHints, "adaptation hint");
        if (adaptationHints.isEmpty()) {
            adaptationHints.add("把戏剧冲突最强的情节亮点直观呈现出来。");
        }

        // 处理 characters 数组：将纯字符串元素转换为对象格式 { name: "xxx", aliases: [] }
        ArrayNode characters = ensureArray(root, "characters");
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
                putIfBlank(characterNode, "name", "Character " + (i + 1));
                ensureArray(characterNode, "aliases");
            }
        }

        // 处理 locations 数组：将纯字符串元素转换为对象格式 { name: "xxx", type: "interior" }
        ArrayNode locations = ensureArray(root, "locations");
        for (int i = 0; i < locations.size(); i++) {
            JsonNode item = locations.get(i);
            if (item != null && item.isTextual()) {
                ObjectNode locationNode = objectMapper.createObjectNode();
                locationNode.put("name", item.asText());
                locations.set(i, locationNode);
                item = locationNode;
            }
            if (item instanceof ObjectNode locationNode) {
                putIfBlank(locationNode, "name", "Location " + (i + 1));
                putIfBlank(locationNode, "type", "interior");
            }
        }

        // 为空数组提供 fallback 默认值
        if (conflicts.isEmpty()) {
            conflicts.add("Retain the chapter's core conflict in the adaptation.");
        }
        if (openThreads.isEmpty()) {
            openThreads.add("留下一处未解决的悬念伏笔，延续到后续场景之中。");
        }
    }

    /**
     * 标准化故事圣经 JSON
     * - 补全主线剧情
     * - 确保 characters 和 locations 数组非空，必要时根据章节摘要合成
     *
     * @param root            故事圣经 JSON 节点
     * @param chapterDigests  章节摘要列表（用于 fallback 合成角色/地点）
     */
    private void normalizeStoryBible(ObjectNode root, List<ChapterDigest> chapterDigests) {
        putIfBlank(root, "mainPlot", buildMainPlotFallback(chapterDigests));
        ensureArray(root, "themes");
        ensureArray(root, "continuityRules");

        // 处理 characters 数组
        ArrayNode charactersNode = ensureArray(root, "characters");
        if (charactersNode.isEmpty()) {
            // 如果 AI 没返回任何角色，从章节摘要中提取角色名来合成
            synthesizeCharacters(charactersNode, chapterDigests);
        } else {
            // 为已有角色补全缺失字段
            for (int i = 0; i < charactersNode.size(); i++) {
                JsonNode item = charactersNode.get(i);
                if (item instanceof ObjectNode characterNode) {
                    putIfBlank(characterNode, "id", "char_%03d".formatted(i + 1));
                    putIfBlank(characterNode, "name", "Character " + (i + 1));
                    putIfBlank(characterNode, "role", i == 0 ? "protagonist" : "supporting");
                    ensureArray(characterNode, "aliases");
                    ensureArray(characterNode, "traits");
                }
            }
        }

        // 处理 locations 数组
        ArrayNode locationsNode = ensureArray(root, "locations");
        if (locationsNode.isEmpty()) {
            synthesizeLocations(locationsNode, chapterDigests);
        } else {
            for (int i = 0; i < locationsNode.size(); i++) {
                JsonNode item = locationsNode.get(i);
                if (item instanceof ObjectNode locationNode) {
                    putIfBlank(locationNode, "id", "loc_%03d".formatted(i + 1));
                    putIfBlank(locationNode, "name", "Location " + (i + 1));
                    putIfBlank(locationNode, "type", "interior");
                }
            }
        }
    }

    /**
     * 标准化场景规划 JSON
     * - 确保 scenes 数组非空，必要时根据章节摘要合成场景
     * - 为每个场景补全 id、标题、locationId、timeOfDay、角色、戏剧目的、摘要、必要节拍
     *
     * @param root            场景规划 JSON 节点
     * @param chapterDigests  章节摘要列表（用于 fallback）
     * @param storyBible      故事圣经（用于 fallback 角色和地点）
     */
    private void normalizeScenePlan(ObjectNode root, List<ChapterDigest> chapterDigests, StoryBible storyBible) {
        ArrayNode scenesNode = ensureArray(root, "scenes");

        // 如果场景规划为空，根据章节摘要自动生成默认场景
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
                sceneNode.put("dramaticPurpose", "Move the adaptation conflict forward.");
                sceneNode.put("summary", digest.summary());
                ArrayNode requiredBeats = sceneNode.putArray("requiredBeats");
                digest.majorEvents().forEach(requiredBeats::add);
                if (requiredBeats.isEmpty()) {
                    requiredBeats.add("保留核心戏剧冲突高潮点。");
                }
            }
        } else {
            // 为已有场景补全缺失字段
            for (int i = 0; i < scenesNode.size(); i++) {
                ChapterDigest digest = chapterDigests.get(Math.min(i, chapterDigests.size() - 1));
                JsonNode item = scenesNode.get(i);
                if (item instanceof ObjectNode sceneNode) {
                    putIfBlank(sceneNode, "id", "scene_%03d".formatted(i + 1));
                    putIfBlank(sceneNode, "title", digest.title());

                    ArrayNode sourceChapters = ensureArray(sceneNode, "sourceChapters");
                    if (sourceChapters.isEmpty()) {
                        sourceChapters.add(digest.chapterIndex());
                    }

                    putIfBlank(sceneNode, "locationId", storyBible.locations().getFirst().id());
                    putIfBlank(sceneNode, "timeOfDay", i == 0 ? "night" : "day");

                    ArrayNode characters = ensureArray(sceneNode, "characters");
                    if (characters.isEmpty()) {
                        storyBible.characters().stream().limit(2).map(BibleCharacter::id).forEach(characters::add);
                    }

                    putIfBlank(sceneNode, "dramaticPurpose", "Move the adaptation conflict forward.");
                    putIfBlank(sceneNode, "summary", digest.summary());

                    ArrayNode requiredBeats = ensureArray(sceneNode, "requiredBeats");
                    if (requiredBeats.isEmpty()) {
                        digest.majorEvents().forEach(requiredBeats::add);
                        if (requiredBeats.isEmpty()) {
                            requiredBeats.add("保留核心戏剧冲突高潮点。");
                        }
                    }
                }
            }
        }
    }

    /**
     * 标准化场景草稿 JSON
     * - 补全 id、标题、locationId、timeOfDay、戏剧目的、摘要等基础字段
     * - 确保 blocks 数组非空，必要时根据场景规划生成默认 block
     * - 处理对话块中缺失的 speakerId
     *
     * @param root     场景草稿 JSON 节点
     * @param context  场景草稿上下文（包含规划场景等）
     */
    private void normalizeSceneDraft(ObjectNode root, SceneDraftContext context) {
        PlannedScene scene = context.plannedScene();

        // 补全顶层基础字段
        putIfBlank(root, "id", scene.id());
        putIfBlank(root, "title", scene.title());
        putIfBlank(root, "locationId", scene.locationId());
        putIfBlank(root, "timeOfDay", scene.timeOfDay());
        putIfBlank(root, "dramaticPurpose", scene.dramaticPurpose());
        putIfBlank(root, "summary", scene.summary());

        // 补全来源章节数组
        ArrayNode sourceChapters = ensureArray(root, "sourceChapters");
        if (sourceChapters.isEmpty()) {
            scene.sourceChapters().forEach(sourceChapters::add);
        }

        // 补全出场角色数组
        ArrayNode characters = ensureArray(root, "characters");
        if (characters.isEmpty()) {
            scene.characters().forEach(characters::add);
        }

        // 处理 blocks（具体场景内容块）
        ArrayNode blocks = ensureArray(root, "blocks");
        if (blocks.isEmpty()) {
            // 如果完全没有 block，生成一个默认动作块 + 一段对话
            ObjectNode action = blocks.addObject();
            action.put("type", "action");
            action.put("text", scene.summary());

            if (!scene.characters().isEmpty()) {
                ObjectNode dialogue = blocks.addObject();
                dialogue.put("type", "dialogue");
                dialogue.put("speakerId", scene.characters().getFirst());
                dialogue.put("text", "我们不能停下。");
            }
        } else {
            // 为每个 block 补全 type 和 text，对话块还需补全 speakerId
            for (int i = 0; i < blocks.size(); i++) {
                JsonNode item = blocks.get(i);
                if (item instanceof ObjectNode blockNode) {
                    putIfBlank(blockNode, "type", "action");
                    putIfBlank(blockNode, "text", scene.summary());

                    // 对话块缺少 speakerId 时，用场景的第一个角色补上
                    if ("dialogue".equals(blockNode.path("type").asText())
                            && isBlank(blockNode.path("speakerId").asText())
                            && !scene.characters().isEmpty()) {
                        blockNode.put("speakerId", scene.characters().getFirst());
                    }
                }
            }
        }
    }

    // 合成方法（当 AI 输出为空时使用）

    /**
     * 从章节摘要中提取角色名，合成为故事圣经所需的角色数组
     *
     * @param charactersNode  待填充的角色数组节点
     * @param chapterDigests  章节摘要列表
     */
    private void synthesizeCharacters(ArrayNode charactersNode, List<ChapterDigest> chapterDigests) {
        Set<String> names = new LinkedHashSet<>();
        for (ChapterDigest digest : chapterDigests) {
            for (CharacterMention mention : digest.characters()) {
                names.add(mention.name());
            }
        }
        if (names.isEmpty()) {
            names.add("Protagonist");
        }

        int index = 1;
        for (String name : names) {
            ObjectNode character = charactersNode.addObject();
            character.put("id", "char_%03d".formatted(index));
            character.put("name", name);
            character.putArray("aliases");
            character.put("role", index == 1 ? "protagonist" : "supporting");
            character.put("goal", index == 1 ? "Pursue the core truth." : "Support the scene conflict.");
            character.putArray("traits");
            index++;
        }
    }

    /**
     * 从章节摘要中提取地点名，合成为故事圣经所需的地点数组
     *
     * @param locationsNode   待填充的地点数组节点
     * @param chapterDigests  章节摘要列表
     */
    private void synthesizeLocations(ArrayNode locationsNode, List<ChapterDigest> chapterDigests) {
        Set<String> names = new LinkedHashSet<>();
        for (ChapterDigest digest : chapterDigests) {
            for (LocationMention mention : digest.locations()) {
                names.add(mention.name());
            }
        }
        if (names.isEmpty()) {
            names.add("Primary Location");
        }

        int index = 1;
        for (String name : names) {
            ObjectNode location = locationsNode.addObject();
            location.put("id", "loc_%03d".formatted(index));
            location.put("name", name);
            location.put("type", "interior");
            index++;
        }
    }

    /**
     * 从章节摘要中构建主线剧情的 fallback
     *
     * @param chapterDigests  章节摘要列表
     * @return 截取后的主线剧情文本
     */
    private String buildMainPlotFallback(List<ChapterDigest> chapterDigests) {
        String joined = chapterDigests.stream()
                .map(ChapterDigest::summary)
                .filter(summary -> summary != null && !summary.isBlank())
                .findFirst()
                .orElse("A protagonist uncovers the core conflict and keeps moving toward the truth.");
        return summarize(joined, 160);
    }

    // 通用工具方法
    /**
     * 确保对象节点中存在指定的数组字段，若不存在则创建空数组
     *
     * @param node       目标 JSON 对象节点
     * @param fieldName  字段名
     * @return 数组节点（原有或新建）
     */
    private ArrayNode ensureArray(ObjectNode node, String fieldName) {
        JsonNode current = node.get(fieldName);
        if (current instanceof ArrayNode arrayNode) {
            return arrayNode;
        }
        ArrayNode replacement = objectMapper.createArrayNode();
        node.set(fieldName, replacement);
        return replacement;
    }

    /**
     * 如果字段值为空，则设置 fallback 值
     *
     * @param node       目标 JSON 对象节点
     * @param fieldName  字段名
     * @param fallback   备选值
     */
    private void putIfBlank(ObjectNode node, String fieldName, String fallback) {
        JsonNode current = node.get(fieldName);
        if (current == null || current.isNull() || isBlank(current.asText())) {
            node.put(fieldName, fallback);
        }
    }

    /**
     * 将字符串数组中的非字符串元素（如对象）扁平化为字符串
     * 用于兼容 AI 输出混用对象和字符串的情况
     *
     * @param arrayNode     目标数组节点
     * @param fallbackLabel 当无法提取内容时的 fallback 标签
     */
    private void normalizeStringArray(ArrayNode arrayNode, String fallbackLabel) {
        for (int i = 0; i < arrayNode.size(); i++) {
            JsonNode item = arrayNode.get(i);
            if (item == null || item.isNull()) {
                arrayNode.set(i, objectMapper.getNodeFactory().textNode("Keep one " + fallbackLabel + " visible."));
                continue;
            }
            if (item.isTextual()) {
                continue;
            }
            if (item.isObject()) {
                // 尝试从常见字段中提取文本
                String extracted = firstNonBlank(
                        item.path("text").asText(null),
                        item.path("summary").asText(null),
                        item.path("description").asText(null),
                        item.path("content").asText(null),
                        item.path("name").asText(null),
                        item.path("label").asText(null)
                );
                arrayNode.set(i, objectMapper.getNodeFactory().textNode(
                        extracted != null ? extracted : "Keep one " + fallbackLabel + " visible."
                ));
                continue;
            }
            arrayNode.set(i, objectMapper.getNodeFactory().textNode(item.asText()));
        }
    }

    /**
     * 返回第一个非空白字符串
     *
     * @param values 待检查的字符串数组
     * @return 第一个非空非空白的字符串，若全为空则返回 null
     */
    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * 判断字符串是否为 null 或空白
     *
     * @param value 待检查的字符串
     * @return true 表示 null 或仅包含空白字符
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 截取字符串到指定最大长度，超出部分用 "..." 代替
     *
     * @param value     原始字符串
     * @param maxLength 最大长度
     * @return 截取后的字符串
     */
    private String summarize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "Preserve the chapter's strongest dramatic beat.";
        }
        String normalized = value.strip();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    /**
     * 将对象序列化为格式化的 JSON 字符串（用于构建 prompt）
     *
     * @param value 待序列化的对象
     * @return 格式化后的 JSON 字符串
     * @throws IllegalStateException 序列化失败时抛出
     */
    private String toJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize staged prompt input", exception);
        }
    }

    // 内部 Record 定义（用于构建 Prompt 输入结构）

    /** 章节摘要阶段的 Prompt 输入结构 */
    private record ChapterDigestPromptInput(
            String projectTitle,
            int chapterIndex,
            String chapterTitle,
            String chapterContent,
            GenerationOptions options
    ) {
    }

    /** 故事圣经阶段的 Prompt 输入结构 */
    private record StoryBiblePromptInput(
            String projectTitle,
            List<ChapterDigest> chapterDigests,
            GenerationOptions options
    ) {
    }

    /** 场景规划阶段的 Prompt 输入结构 */
    private record ScenePlanPromptInput(
            String projectTitle,
            List<ChapterDigest> chapterDigests,
            StoryBible storyBible,
            GenerationOptions options
    ) {
    }

    /** 场景草稿阶段的 Prompt 输入结构 */
    private record SceneDraftPromptInput(
            String projectTitle,
            PlannedScene plannedScene,
            List<SourceChapterPromptInput> sourceChapters,
            List<BibleCharacter> characters,
            BibleLocation location,
            List<String> continuityRules,
            String previousSceneSummary,
            GenerationOptions options
    ) {
        /**
         * 从 SceneDraftContext 构建 SceneDraftPromptInput
         */
        private static SceneDraftPromptInput from(NovelProject project, SceneDraftContext context,
                                                  GenerationOptions options) {
            return new SceneDraftPromptInput(
                    project.getTitle(),
                    context.plannedScene(),
                    context.sourceChapters().stream()
                            .map(SourceChapterPromptInput::from)
                            .toList(),
                    context.characters(),
                    context.location(),
                    context.continuityRules(),
                    context.previousSceneSummary(),
                    options
            );
        }
    }

    /** 来源章节的 Prompt 输入结构（精简后的章节信息） */
    private record SourceChapterPromptInput(
            int chapterIndex,
            String title,
            String content
    ) {
        private static SourceChapterPromptInput from(NovelChapter chapter) {
            return new SourceChapterPromptInput(
                    chapter.getChapterIndex(),
                    chapter.getTitle(),
                    chapter.getContent()
            );
        }
    }
}