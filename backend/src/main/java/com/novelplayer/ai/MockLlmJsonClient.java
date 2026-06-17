package com.novelplayer.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelplayer.application.generation.GenerationStageNames;
import com.novelplayer.application.generation.model.BibleCharacter;
import com.novelplayer.application.generation.model.BibleLocation;
import com.novelplayer.application.generation.model.ChapterDigest;
import com.novelplayer.application.generation.model.CharacterMention;
import com.novelplayer.application.generation.model.DraftSceneBlock;
import com.novelplayer.application.generation.model.LocationMention;
import com.novelplayer.application.generation.model.PlannedScene;
import com.novelplayer.application.generation.model.SceneDraft;
import com.novelplayer.application.generation.model.ScenePlan;
import com.novelplayer.application.generation.model.StoryBible;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM JSON 客户端的本地模拟实现。
 *
 * <p>该实现不调用真实大语言模型，而是根据输入提示词中的 JSON 参数返回
 * 稳定的模拟中间模型。主要用于在无网络环境或开发初期验证多阶段
 * 生成流水线的完整性和数据流转逻辑。</p>
 *
 * <p><strong>注意：</strong>此实现仅适用于模拟测试场景，不应在生产环境中使用。
 * 各阶段返回的数据固定且可预测，便于单元测试和集成测试。</p>
 *
 * @see LlmJsonClient
 * @see GenerationStageNames
 */
@Component
@ConditionalOnProperty(prefix = "novel-player.generation", name = "mock-ai", havingValue = "true", matchIfMissing = true)
public class MockLlmJsonClient implements LlmJsonClient {

    private static final Pattern CHARACTER_ACTION_PATTERN = Pattern.compile(
            "(?:^|[，。！？；\\s])([\\p{IsHan}]{2,6})(?=(?:推开|翻开|发现|认出|听见|看见|说|问|告诉|沉默|站在|站|塞进|拿|换|知道|害怕|失踪))"
    );

    private static final Pattern ROLE_CHARACTER_PATTERN = Pattern.compile(
            "(陌生男人|父亲|母亲|店主|老板|警察|店员|男人|女人)"
    );

    private static final Pattern LOCATION_PATTERN = Pattern.compile(
            "([\\p{IsHan}]{0,4}(?:旧书店|书店|柜台|门口|雨里|街头|巷口|房间|医院|学校|车站|仓库|天台|办公室|客厅|卧室|走廊))"
    );

    private static final Set<String> CHARACTER_STOPWORDS = Set.of(
            "第一章", "第二章", "第三章", "第四章", "雨夜", "来信", "缺页", "交易",
            "深夜", "傍晚", "白天", "第七页", "旧书店", "书页", "脚步声", "所有人", "名字"
    );

    private static final Set<String> CHARACTER_REJECT_PREFIXES = Set.of(
            "她", "他", "它", "我", "你", "这", "那", "但", "只", "把", "并", "却"
    );

    private static final Set<String> CHARACTER_REJECT_CONTAINS = Set.of(
            "自己", "为什么", "一句话", "没有人", "信封", "口袋", "钢笔", "字迹"
    );

    private final ObjectMapper objectMapper;

    /**
     * 创建模拟 LLM JSON 客户端。
     *
     * @param objectMapper JSON 序列化/反序列化工具。
     */
    public MockLlmJsonClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 根据阶段名称生成模拟的 JSON 响应。
     *
     * <p>该方法从用户提示词中提取 JSON 输入参数，根据不同的阶段名称
     * 分发到对应的模拟构建方法，并返回对应中间模型对象的 JSON 序列化结果。</p>
     *
     * @param stageName 生成阶段名称，用于决定构建哪种类型的模拟响应。
     * @param systemPrompt 系统提示词（本实现中未使用，保留用于接口兼容）。
     * @param userPrompt 用户提示词，必须包含符合规范的 JSON 输入数据。
     * @return 对应阶段中间模型对象的 JSON 序列化字符串。
     * @throws IllegalArgumentException 当阶段名称不受支持时抛出。
     * @throws IllegalStateException 当 JSON 序列化失败时抛出。
     */
    @Override
    public String requestJson(String stageName, String systemPrompt, String userPrompt) {
        JsonNode input = extractInput(userPrompt);
        Object result;
        if (stageName.startsWith(GenerationStageNames.CHAPTER_DIGEST)) {
            result = buildChapterDigest(input);
        } else if (stageName.equals(GenerationStageNames.STORY_BIBLE)) {
            result = buildStoryBible(input);
        } else if (stageName.equals(GenerationStageNames.SCENE_PLAN)) {
            result = buildScenePlan(input);
        } else if (stageName.startsWith(GenerationStageNames.SCENE_DRAFT)) {
            result = buildSceneDraft(input);
        } else {
            throw new IllegalArgumentException("不支持的模拟阶段：" + stageName);
        }

        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception exception) {
            throw new IllegalStateException("序列化模拟阶段结果失败：" + stageName, exception);
        }
    }

    /**
     * 构建模拟的章节摘要。
     *
     * 返回一个固定的章节摘要对象
     *
     * @param input 从提示词中提取的 JSON 输入数据，包含 chapterIndex、chapterTitle 和 chapterContent。
     * @return 包含模拟摘要数据的 ChapterDigest 对象。
     */
    private ChapterDigest buildChapterDigest(JsonNode input) {
        int chapterIndex = input.path("chapterIndex").asInt(1);
        String title = input.path("chapterTitle").asText("第" + chapterIndex + "章");
        String content = input.path("chapterContent").asText("");
        String sourceText = title + "\n" + content;
        List<CharacterMention> characters = extractCharacterMentions(sourceText);
        List<LocationMention> locations = extractLocationMentions(sourceText);
        List<String> majorEvents = extractMajorEvents(title, content);
        List<String> conflicts = extractConflicts(content, characters);
        List<String> openThreads = extractOpenThreads(content);
        List<String> adaptationHints = buildAdaptationHints(title, locations, conflicts, openThreads);

        return new ChapterDigest(
                chapterIndex,
                title,
                summarize(content, 90),
                majorEvents,
                characters,
                locations,
                conflicts,
                openThreads,
                adaptationHints
        );
    }

    /**
     * 构建模拟的故事圣经。
     *
     * 返回一个固定的故事圣经对象
     *
     * @param input 从提示词中提取的 JSON 输入数据，包含 projectTitle 和 chapterDigests。
     * @return 包含模拟圣经数据的 StoryBible 对象。
     */
    private StoryBible buildStoryBible(JsonNode input) {
        String projectTitle = input.path("projectTitle").asText("项目");
        JsonNode digestsNode = input.path("chapterDigests");
        int digestCount = digestsNode.isArray() ? digestsNode.size() : 1;

        LinkedHashMap<String, CharacterSeed> characterSeeds = new LinkedHashMap<>();
        LinkedHashMap<String, LocationSeed> locationSeeds = new LinkedHashMap<>();
        StringBuilder combinedText = new StringBuilder(projectTitle);

        if (digestsNode.isArray()) {
            for (JsonNode digestNode : digestsNode) {
                combinedText.append(' ').append(digestNode.path("summary").asText(""));
                digestNode.path("characters").forEach(item -> {
                    String name = item.path("name").asText("").strip();
                    if (!isLikelyCharacterName(name)) {
                        return;
                    }
                    CharacterSeed seed = characterSeeds.computeIfAbsent(name, ignored -> new CharacterSeed(name));
                    seed.aliases().addAll(readStringList(item.path("aliases")));
                    seed.roleHints().add(item.path("roleHint").asText(""));
                    seed.goalHints().add(item.path("goalHint").asText(""));
                    seed.incrementCount();
                });
                digestNode.path("locations").forEach(item -> {
                    String name = item.path("name").asText("").strip();
                    if (name.isBlank()) {
                        return;
                    }
                    LocationSeed seed = locationSeeds.computeIfAbsent(name, ignored -> new LocationSeed(
                            name,
                            normalizeLocationType(item.path("type").asText("")),
                            item.path("description").asText("")
                    ));
                    seed.incrementCount();
                });
                digestNode.path("conflicts").forEach(item -> combinedText.append(' ').append(item.asText("")));
                digestNode.path("openThreads").forEach(item -> combinedText.append(' ').append(item.asText("")));
            }
        }

        if (characterSeeds.isEmpty()) {
            CharacterSeed fallback = new CharacterSeed("主角");
            fallback.aliases().add("她");
            fallback.incrementCount();
            characterSeeds.put(fallback.name(), fallback);
        }

        if (locationSeeds.isEmpty()) {
            LocationSeed fallback = new LocationSeed("核心场景", "interior", "由章节摘要归并出的主要冲突场所");
            fallback.incrementCount();
            locationSeeds.put(fallback.name(), fallback);
        }

        List<BibleCharacter> characters = new ArrayList<>();
        int characterIndex = 1;
        String protagonistName = characterSeeds.keySet().iterator().next();
        for (CharacterSeed seed : characterSeeds.values()) {
            String role = inferBibleRole(seed.name(), protagonistName.equals(seed.name()));
            characters.add(new BibleCharacter(
                    "char_%03d".formatted(characterIndex),
                    seed.name(),
                    List.copyOf(seed.aliases()),
                    role,
                    inferCharacterGoal(seed.name(), role, seed.goalHints()),
                    inferCharacterTraits(seed.name(), role),
                    inferCharacterVoice(seed.name(), role)
            ));
            characterIndex++;
        }

        List<BibleLocation> locations = new ArrayList<>();
        int locationIndex = 1;
        for (LocationSeed seed : locationSeeds.values()) {
            locations.add(new BibleLocation(
                    "loc_%03d".formatted(locationIndex),
                    seed.name(),
                    seed.type(),
                    seed.description().isBlank() ? "由章节内容归并出的关键场景" : seed.description()
            ));
            locationIndex++;
        }

        String mainPlot = buildMainPlot(projectTitle, protagonistName, digestCount, combinedText.toString());
        List<String> themes = inferThemes(combinedText.toString());
        List<String> continuityRules = inferContinuityRules(combinedText.toString(), protagonistName);

        return new StoryBible(characters, locations, mainPlot, themes, continuityRules);
    }

    /**
     * 构建模拟的场景规划。
     *
     * @param input 从提示词中提取的 JSON 输入数据，包含 chapterDigests 和 storyBible。
     * @return 包含模拟规划数据的 ScenePlan 对象。
     */
    private ScenePlan buildScenePlan(JsonNode input) {
        JsonNode digestsNode = input.path("chapterDigests");
        JsonNode storyBibleNode = input.path("storyBible");
        String previousTimeOfDay = null;
        Map<String, String> characterIdsByName = indexCharacterIdsByName(storyBibleNode.path("characters"));
        Map<String, String> locationIdsByName = indexLocationIdsByName(storyBibleNode.path("locations"));
        String fallbackLocationId = storyBibleNode.path("locations").path(0).path("id").asText("loc_001");
        List<String> fallbackCharacterIds = firstCharacterIds(storyBibleNode.path("characters"), 2);

        List<PlannedScene> scenes = new ArrayList<>();
        for (int i = 0; i < digestsNode.size(); i++) {
            JsonNode digest = digestsNode.get(i);
            int chapterIndex = digest.path("chapterIndex").asInt(i + 1);
            List<String> requiredBeats = new ArrayList<>(readStringList(digest.path("majorEvents")));
            if (requiredBeats.isEmpty()) {
                requiredBeats.add("建立场景目标");
                requiredBeats.add("制造角色阻力");
            }
            if (requiredBeats.size() < 3) {
                readStringList(digest.path("openThreads")).stream()
                        .filter(item -> !item.isBlank() && !requiredBeats.contains(item))
                        .findFirst()
                        .ifPresent(requiredBeats::add);
            }
            String locationId = resolveLocationId(digest.path("locations"), locationIdsByName, fallbackLocationId);
            List<String> characterIds = resolveCharacterIds(digest.path("characters"), characterIdsByName, fallbackCharacterIds);
            String summary = digest.path("summary").asText("保留该章的核心冲突。");
            String timeOfDay = inferTimeOfDay(digest.path("title").asText("") + " " + summary, previousTimeOfDay);
            previousTimeOfDay = timeOfDay;
            scenes.add(new PlannedScene(
                    "scene_%03d".formatted(i + 1),
                    digest.path("title").asText("场景" + (i + 1)),
                    List.of(chapterIndex),
                    locationId,
                    timeOfDay,
                    characterIds,
                    buildDramaticPurpose(chapterIndex, digest),
                    summary,
                    List.copyOf(requiredBeats)
            ));
        }

        return new ScenePlan(scenes);
    }

    /**
     * 构建模拟的分场草稿。
     *
     * 返回一个固定的分场草稿对象
     *
     * @param input 从提示词中提取的 JSON 输入数据，包含 plannedScene 场景规划信息。
     * @return 包含模拟草稿数据的 SceneDraft 对象。
     */
    private SceneDraft buildSceneDraft(JsonNode input) {
        JsonNode plannedScene = input.path("plannedScene");
        List<Integer> sourceChapters = readIntegerList(plannedScene.path("sourceChapters"));
        List<String> characters = readStringList(plannedScene.path("characters"));
        JsonNode sourceChapterNode = input.path("sourceChapters").path(0);
        String sourceContent = sourceChapterNode.path("content").asText(plannedScene.path("summary").asText(""));
        String firstCharacterId = characters.isEmpty() ? "char_001" : characters.get(0);
        String secondCharacterId = characters.size() > 1 ? characters.get(1) : firstCharacterId;
        String summary = plannedScene.path("summary").asText("保留当前场景的核心矛盾。");

        return new SceneDraft(
                plannedScene.path("id").asText("scene_001"),
                plannedScene.path("title").asText("场景"),
                sourceChapters,
                plannedScene.path("locationId").asText("loc_001"),
                plannedScene.path("timeOfDay").asText("夜晚"),
                characters,
                plannedScene.path("dramaticPurpose").asText("推进核心冲突"),
                summary,
                List.of(
                        new DraftSceneBlock("动作描述", null, firstActionBlock(summary, sourceContent)),
                        new DraftSceneBlock("对白", firstCharacterId, firstDialogue(summary, sourceContent)),
                        new DraftSceneBlock("对白", secondCharacterId, secondDialogue(summary, sourceContent)),
                        new DraftSceneBlock("转场", null, transitionBlock(summary))
                )
        );
    }

    /**
     * 从用户提示词中提取 JSON 输入数据。
     *
     * <p>该方法查找提示词中第一个 '{' 和最后一个 '}' 之间的内容，
     * 并解析为 JsonNode 供后续构建方法使用。</p>
     *
     * @param userPrompt 包含 JSON 数据的用户提示词。
     * @return 解析后的 JSON 输入节点。
     * @throws IllegalArgumentException 当提示词中未包含有效的 JSON 数据时抛出。
     * @throws IllegalStateException 当 JSON 解析失败时抛出。
     */
    private JsonNode extractInput(String userPrompt) {
        String json = extractLastTopLevelObject(userPrompt);
        if (json == null) {
            throw new IllegalArgumentException("MockLlmJsonClient 未找到提示词输入 JSON 数据");
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalStateException("提示词输入 JSON 解析失败", exception);
        }
    }

    private String extractLastTopLevelObject(String text) {
        int depth = 0;
        int start = -1;
        int candidateStart = -1;
        int candidateEnd = -1;
        boolean inString = false;
        boolean escaping = false;

        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (inString) {
                if (escaping) {
                    escaping = false;
                } else if (current == '\\') {
                    escaping = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }

            if (current == '"') {
                inString = true;
                continue;
            }
            if (current == '{') {
                if (depth == 0) {
                    start = index;
                }
                depth++;
                continue;
            }
            if (current == '}') {
                if (depth == 0) {
                    continue;
                }
                depth--;
                if (depth == 0 && start >= 0) {
                    candidateStart = start;
                    candidateEnd = index;
                }
            }
        }

        if (candidateStart < 0 || candidateEnd < candidateStart) {
            return null;
        }
        return text.substring(candidateStart, candidateEnd + 1);
    }
    /**
     * 构建模拟的场景修复结果。
     *
     * <p>返回一个固定的修复后分场草稿，保留原有场景的核心结构（ID、源章节、角色列表、地点），
     * 同时从待修复草稿和场景规划中回填标题、时间、目的、摘要等字段，
     * 并生成固定的动作块和对白块作为修复后的内容。</p>
     *
     * @param input 从提示词中提取的 JSON 输入数据，包含 plannedScene（场景规划）和 draftToRepair（待修复草稿）。
     * @return 包含模拟修复数据的 SceneDraft 对象。
     */
    /**
     * 从角色列表中提取指定数量的角色 ID。
     *
     * <p>按顺序提取前 N 个有效角色 ID，若列表为空则返回默认 ID "char_001"。</p>
     *
     * @param charactersNode 角色列表的 JSON 节点。
     * @param limit 需要提取的最大角色数量。
     * @return 提取到的角色 ID 列表。
     */
    private List<String> firstCharacterIds(JsonNode charactersNode, int limit) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < charactersNode.size() && ids.size() < limit; i++) {
            String id = charactersNode.get(i).path("id").asText(null);
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            ids.add("char_001");
        }
        return List.copyOf(ids);
    }

    /**
     * 从 JSON 数组节点中读取字符串列表。
     *
     * @param arrayNode 可能包含字符串数组的 JSON 节点。
     * @return 解析后的字符串列表，若节点不是数组则返回空列表。
     */
    private List<String> readStringList(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        if (!arrayNode.isArray()) {
            return values;
        }
        for (JsonNode item : arrayNode) {
            if (item != null && !item.isNull()) {
                values.add(item.asText());
            }
        }
        return List.copyOf(values);
    }

    /**
     * 从 JSON 数组节点中读取整数列表。
     *
     * @param arrayNode 可能包含整数数组的 JSON 节点。
     * @return 解析后的整数列表，若节点不是数组则返回空列表。
     */
    private List<Integer> readIntegerList(JsonNode arrayNode) {
        List<Integer> values = new ArrayList<>();
        if (!arrayNode.isArray()) {
            return values;
        }
        for (JsonNode item : arrayNode) {
            if (item != null && item.canConvertToInt()) {
                values.add(item.asInt());
            }
        }
        return List.copyOf(values);
    }

    /**
     * 将文本压缩到指定长度，超出部分用省略号替代。
     *
     * @param value 原始文本。
     * @param maxLength 最大允许长度。
     * @return 压缩后的摘要文本，若原始文本为空则返回占位文本。
     */
    private String summarize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "当前章节缺少可用正文，保留为简化摘要。";
        }
        String normalized = value.strip();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private List<CharacterMention> extractCharacterMentions(String text) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Matcher actionMatcher = CHARACTER_ACTION_PATTERN.matcher(text);
        while (actionMatcher.find()) {
            String candidate = normalizeCharacterName(actionMatcher.group(1));
            if (isLikelyCharacterName(candidate)) {
                names.add(candidate);
            }
        }
        Matcher roleMatcher = ROLE_CHARACTER_PATTERN.matcher(text);
        while (roleMatcher.find()) {
            String candidate = normalizeCharacterName(roleMatcher.group(1));
            if (isLikelyCharacterName(candidate)) {
                names.add(candidate);
            }
        }
        names = normalizeCharacterSet(names);
        if (names.isEmpty()) {
            names.add("主角");
        }

        List<CharacterMention> mentions = new ArrayList<>();
        int index = 0;
        for (String name : names) {
            boolean protagonist = index == 0;
            mentions.add(new CharacterMention(
                    name,
                    defaultAliases(name, protagonist),
                    protagonist ? "protagonist" : inferMentionRole(name),
                    protagonist ? "追查线索并逼近真相" : inferMentionGoal(name)
            ));
            index++;
        }
        return List.copyOf(mentions);
    }

    private List<LocationMention> extractLocationMentions(String text) {
        LinkedHashMap<String, LocationMention> locations = new LinkedHashMap<>();
        Matcher matcher = LOCATION_PATTERN.matcher(text);
        while (matcher.find()) {
            String name = normalizeLocationName(matcher.group(1));
            if (name.isBlank()) {
                continue;
            }
            locations.putIfAbsent(name, new LocationMention(
                    name,
                    inferLocationTypeFromName(name),
                    "当前章节中发生关键冲突的场所"
            ));
        }
        if (locations.isEmpty()) {
            locations.put("核心场景", new LocationMention("核心场景", "interior", "从章节内容抽象出的主要冲突发生地"));
        }
        return List.copyOf(locations.values());
    }

    private List<String> extractMajorEvents(String title, String content) {
        List<String> events = new ArrayList<>();
        for (String sentence : splitSentences(content)) {
            if (sentence.length() < 4) {
                continue;
            }
            events.add(summarize(sentence, 28));
            if (events.size() == 3) {
                break;
            }
        }
        if (events.isEmpty()) {
            events.add("围绕《" + title + "》建立新的悬念。");
        }
        return List.copyOf(events);
    }

    private List<String> extractConflicts(String content, List<CharacterMention> characters) {
        LinkedHashSet<String> conflicts = new LinkedHashSet<>();
        String protagonist = characters.isEmpty() ? "主角" : characters.getFirst().name();
        if (content.contains("失踪")) {
            conflicts.add(protagonist + "被迫追查失踪背后的真相。");
        }
        if (content.contains("不要相信") || content.contains("第七页")) {
            conflicts.add("围绕第七页的秘密产生怀疑与对峙。");
        }
        if (content.contains("换") || content.contains("交易")) {
            conflicts.add("关键线索被拿来交换，双方互相试探。");
        }
        if (content.contains("陌生男人") || content.contains("男人")) {
            conflicts.add("陌生来者的条件让局势骤然紧张。");
        }
        if (conflicts.isEmpty()) {
            conflicts.add("人物目标与外部阻力发生正面碰撞。");
        }
        return List.copyOf(conflicts);
    }

    private List<String> extractOpenThreads(String content) {
        LinkedHashSet<String> threads = new LinkedHashSet<>();
        if (content.contains("父亲") && content.contains("失踪")) {
            threads.add("父亲失踪与最新线索之间的关联仍待确认。");
        }
        if (content.contains("第七页")) {
            threads.add("第七页的内容和去向仍未揭晓。");
        }
        if (content.contains("陌生男人") || content.contains("男人")) {
            threads.add("雨夜出现的男人身份仍不明确。");
        }
        if (content.contains("名字")) {
            threads.add("写着所有人名字的那一页究竟意味着什么。");
        }
        if (threads.isEmpty()) {
            threads.add("留下一个后续场景必须回应的悬念。");
        }
        return List.copyOf(threads);
    }

    private List<String> buildAdaptationHints(String title, List<LocationMention> locations,
                                              List<String> conflicts, List<String> openThreads) {
        String locationName = locations.isEmpty() ? "当前场景" : locations.getFirst().name();
        String conflict = conflicts.isEmpty() ? "当前冲突" : conflicts.getFirst();
        String thread = openThreads.isEmpty() ? "保留悬念" : openThreads.getFirst();
        return List.of(
                "突出" + locationName + "中的悬疑气氛与人物试探。",
                "把“" + conflict + "”改写成可直接表演的对抗场面。",
                "结尾保留“" + thread + "”作为下一场的勾子。"
        );
    }

    private String inferBibleRole(String name, boolean protagonist) {
        if (protagonist) {
            return "protagonist";
        }
        if (name.contains("男人") || name.contains("对手")) {
            return "antagonist";
        }
        return "supporting";
    }

    private String inferCharacterGoal(String name, String role, Set<String> goalHints) {
        for (String hint : goalHints) {
            if (hint != null && !hint.isBlank()) {
                return hint;
            }
        }
        return switch (role) {
            case "protagonist" -> "顺着新出现的线索追查真相";
            case "antagonist" -> "隐藏关键秘密并持续制造阻力";
            default -> name.contains("父亲") ? "与失踪谜团保持关联，推动真相浮出水面" : "推动当前冲突进一步升级";
        };
    }

    private List<String> inferCharacterTraits(String name, String role) {
        if ("protagonist".equals(role)) {
            return List.of("敏锐", "克制", "行动力强");
        }
        if ("antagonist".equals(role)) {
            return List.of("冷静", "强势");
        }
        if (name.contains("父亲")) {
            return List.of("缺席", "牵动全局");
        }
        return List.of("谨慎", "保留秘密");
    }

    private String inferCharacterVoice(String name, String role) {
        if ("protagonist".equals(role)) {
            return "短句为主，情绪压在动作里";
        }
        if ("antagonist".equals(role)) {
            return "语气平稳，常用反问";
        }
        return name.contains("父亲") ? "更多通过回忆和线索间接呈现" : "说话留白，信息不会一次说尽";
    }

    private String buildMainPlot(String projectTitle, String protagonistName, int digestCount, String combinedText) {
        if (combinedText.contains("第七页") && combinedText.contains("父亲")) {
            return projectTitle + "中，" + protagonistName + "因一封来信和消失的第七页，被卷入追查父亲失踪真相的危险试探。";
        }
        return projectTitle + "的主角在" + digestCount + "个章节中连续发现线索，并被迫面对关系和真相的选择。";
    }

    private List<String> inferThemes(String text) {
        LinkedHashSet<String> themes = new LinkedHashSet<>();
        if (text.contains("真相")) {
            themes.add("真相");
        }
        if (text.contains("选择")) {
            themes.add("选择");
        }
        if (text.contains("父亲")) {
            themes.add("亲情");
        }
        if (text.contains("第七页") || text.contains("名字")) {
            themes.add("秘密");
        }
        if (text.contains("交易") || text.contains("换")) {
            themes.add("代价");
        }
        if (themes.isEmpty()) {
            themes.add("悬疑");
            themes.add("关系裂变");
        }
        return themes.stream().limit(3).toList();
    }

    private List<String> inferContinuityRules(String text, String protagonistName) {
        List<String> rules = new ArrayList<>();
        rules.add(protagonistName + "必须随着章节推进逐步接近真相");
        if (text.contains("父亲")) {
            rules.add("父亲失踪的完整真相不能在前期被一次性揭露");
        }
        if (text.contains("第七页")) {
            rules.add("第七页的真实内容要作为中后段核心反转逐步揭开");
        }
        return List.copyOf(rules);
    }

    private Map<String, String> indexCharacterIdsByName(JsonNode charactersNode) {
        Map<String, String> result = new LinkedHashMap<>();
        charactersNode.forEach(node -> {
            String name = node.path("name").asText("").strip();
            String id = node.path("id").asText("").strip();
            if (!name.isBlank() && !id.isBlank()) {
                result.put(name, id);
            }
        });
        return result;
    }

    private Map<String, String> indexLocationIdsByName(JsonNode locationsNode) {
        Map<String, String> result = new LinkedHashMap<>();
        locationsNode.forEach(node -> {
            String name = node.path("name").asText("").strip();
            String id = node.path("id").asText("").strip();
            if (!name.isBlank() && !id.isBlank()) {
                result.put(name, id);
            }
        });
        return result;
    }

    private String resolveLocationId(JsonNode locationMentionsNode, Map<String, String> locationIdsByName,
                                     String fallbackLocationId) {
        for (JsonNode node : locationMentionsNode) {
            String locationId = locationIdsByName.get(node.path("name").asText(""));
            if (locationId != null && !locationId.isBlank()) {
                return locationId;
            }
        }
        return fallbackLocationId;
    }

    private List<String> resolveCharacterIds(JsonNode characterMentionsNode, Map<String, String> characterIdsByName,
                                             List<String> fallbackCharacterIds) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (JsonNode node : characterMentionsNode) {
            String id = characterIdsByName.get(node.path("name").asText(""));
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            ids.addAll(fallbackCharacterIds);
        }
        List<String> ordered = new ArrayList<>(ids);
        if (!fallbackCharacterIds.isEmpty()) {
            String protagonistId = fallbackCharacterIds.getFirst();
            if (ordered.remove(protagonistId)) {
                ordered.addFirst(protagonistId);
            }
        }
        if (ordered.size() > 2) {
            ordered = new ArrayList<>(ordered.subList(0, 2));
        }
        return List.copyOf(ordered);
    }

    private String inferTimeOfDay(String text, String previousTimeOfDay) {
        if (text.contains("夜") || text.contains("深夜") || text.contains("傍晚") || text.contains("雨夜")) {
            return "夜晚";
        }
        if (text.contains("清晨") || text.contains("早晨") || text.contains("上午")
                || text.contains("午后") || text.contains("下午") || text.contains("白天")) {
            return "白天";
        }
        return previousTimeOfDay == null ? "夜晚" : previousTimeOfDay;
    }

    private String buildDramaticPurpose(int chapterIndex, JsonNode digest) {
        List<String> conflicts = readStringList(digest.path("conflicts"));
        if (!conflicts.isEmpty()) {
            return "把第" + chapterIndex + "章里“" + conflicts.getFirst() + "”改写成可表演场面";
        }
        return "把第" + chapterIndex + "章的核心冲突改写成可表演场面";
    }

    private String firstActionBlock(String summary, String sourceContent) {
        List<String> sentences = splitSentences(sourceContent);
        if (!sentences.isEmpty()) {
            return summarize(sentences.getFirst(), 36);
        }
        return summarize(summary, 36);
    }

    private String firstDialogue(String summary, String sourceContent) {
        String text = summary + " " + sourceContent;
        if (text.contains("第七页")) {
            return "第七页到底被谁拿走了？";
        }
        if (text.contains("信")) {
            return "这封信绝不是巧合。";
        }
        if (text.contains("失踪")) {
            return "我一定要把这件事查清楚。";
        }
        return "事情比看上去更复杂。";
    }

    private String secondDialogue(String summary, String sourceContent) {
        String text = summary + " " + sourceContent;
        if (text.contains("换") || text.contains("交易")) {
            return "想要答案，就先把我要的东西交出来。";
        }
        if (text.contains("名字")) {
            return "有些名字一旦被看见，就再也回不去了。";
        }
        if (text.contains("第七页")) {
            return "你确定自己真的准备好看到那一页了吗？";
        }
        return "你确定自己真的知道真相吗？";
    }

    private String transitionBlock(String summary) {
        if (summary.contains("第七页") || summary.contains("名字")) {
            return "转场：新的线索把悬念推向更深处。";
        }
        return "转场：人物带着未解的疑问进入下一场。";
    }

    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        for (String sentence : text.split("[。！？]")) {
            String normalized = sentence.strip();
            if (!normalized.isBlank()) {
                sentences.add(normalized);
            }
        }
        return sentences;
    }

    private String normalizeCharacterName(String candidate) {
        String normalized = candidate == null ? "" : candidate.strip();
        if (normalized.contains("陌生男人")) {
            return "陌生男人";
        }
        if (normalized.contains("父亲")) {
            return "父亲";
        }
        if (normalized.contains("母亲")) {
            return "母亲";
        }
        if (normalized.contains("店主")) {
            return "店主";
        }
        normalized = normalized.replace("一个", "");
        normalized = normalized.replace("那位", "");
        normalized = normalized.replace("这个", "");
        normalized = normalized.replace("那是", "");
        normalized = normalized.replace("他说", "");
        normalized = normalized.replace("她说", "");
        normalized = normalized.replace("男人说", "男人");
        return normalized;
    }

    private boolean isLikelyCharacterName(String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.length() < 2 || candidate.length() > 6) {
            return false;
        }
        if (CHARACTER_STOPWORDS.contains(candidate)) {
            return false;
        }
        for (String prefix : CHARACTER_REJECT_PREFIXES) {
            if (candidate.startsWith(prefix)) {
                return false;
            }
        }
        for (String infix : CHARACTER_REJECT_CONTAINS) {
            if (candidate.contains(infix)) {
                return false;
            }
        }
        if (candidate.endsWith("推开") || candidate.endsWith("翻开") || candidate.endsWith("抬头")
                || candidate.endsWith("沉默") || candidate.endsWith("回答") || candidate.endsWith("看见")) {
            return false;
        }
        if (!candidate.equals("父亲") && !candidate.equals("母亲") && !candidate.equals("店主")
                && candidate.contains("的")) {
            return false;
        }
        return !candidate.endsWith("书店") && !candidate.endsWith("柜台") && !candidate.endsWith("门口");
    }

    private List<String> defaultAliases(String name, boolean protagonist) {
        List<String> aliases = new ArrayList<>();
        if (protagonist) {
            aliases.add("她");
        } else if (name.contains("父亲") || name.contains("男人")) {
            aliases.add("他");
        }
        return List.copyOf(aliases);
    }

    private String inferMentionRole(String name) {
        return name.contains("男人") ? "antagonist" : "supporting";
    }

    private String inferMentionGoal(String name) {
        if (name.contains("父亲")) {
            return "牵动主角持续追查失踪真相";
        }
        if (name.contains("男人")) {
            return "拿关键线索与主角周旋";
        }
        return "推动当前章节的核心冲突";
    }

    private String normalizeLocationName(String value) {
        String normalized = value == null ? "" : value.strip();
        String[] keywords = {
                "旧书店", "书店", "柜台", "门口", "雨里", "街头", "巷口",
                "房间", "医院", "学校", "车站", "仓库", "天台", "办公室", "客厅", "卧室", "走廊"
        };
        for (String keyword : keywords) {
            if (normalized.contains(keyword)) {
                return keyword;
            }
        }
        normalized = normalized.replace("在", "");
        return normalized;
    }

    private String normalizeLocationType(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "interior";
        }
        return normalized.equals("室外") ? "exterior" : normalized;
    }

    private String inferLocationTypeFromName(String locationName) {
        if (locationName.contains("雨里") || locationName.contains("街") || locationName.contains("巷")) {
            return "exterior";
        }
        return "interior";
    }

    private LinkedHashSet<String> normalizeCharacterSet(LinkedHashSet<String> names) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>(names);
        if (normalized.contains("陌生男人")) {
            normalized.remove("男人");
        }
        if (normalized.size() > 1) {
            normalized.remove("主角");
        }
        return normalized;
    }

    private static final class CharacterSeed {

        private final String name;
        private final LinkedHashSet<String> aliases = new LinkedHashSet<>();
        private final LinkedHashSet<String> roleHints = new LinkedHashSet<>();
        private final LinkedHashSet<String> goalHints = new LinkedHashSet<>();
        private int count;

        private CharacterSeed(String name) {
            this.name = name;
        }

        private String name() {
            return name;
        }

        private LinkedHashSet<String> aliases() {
            return aliases;
        }

        private LinkedHashSet<String> roleHints() {
            return roleHints;
        }

        private LinkedHashSet<String> goalHints() {
            return goalHints;
        }

        private void incrementCount() {
            count++;
        }
    }

    private static final class LocationSeed {

        private final String name;
        private final String type;
        private final String description;
        private int count;

        private LocationSeed(String name, String type, String description) {
            this.name = name;
            this.type = type;
            this.description = description == null ? "" : description;
        }

        private String name() {
            return name;
        }

        private String type() {
            return type;
        }

        private String description() {
            return description;
        }

        private void incrementCount() {
            count++;
        }
    }
}
