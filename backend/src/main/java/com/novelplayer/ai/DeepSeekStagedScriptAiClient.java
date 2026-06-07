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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
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
 */
@Component
@ConditionalOnProperty(prefix = "novel-player.generation", name = "mock-ai", havingValue = "false")
public class DeepSeekStagedScriptAiClient implements StagedScriptAiClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekStagedScriptAiClient.class);

    private static final String STAGED_SYSTEM_PROMPT = """
            你是一个中文小说改编结构化生成助手。
            你只能返回一个合法 JSON 对象。
            不要输出 Markdown 代码块，不要输出解释，不要输出 JSON 之外的任何文字。
            所有自然语言内容字段必须使用简体中文输出，并且语言风格要与输入原文一致。
            所有必填字段都必须存在且不能为空。
            只能复用输入上下文里已经出现的人物、地点、章节和场景引用，不允许杜撰新的引用编号。
            JSON 的键名、结构字段名、id、speakerId、locationId、sceneId 这类结构字段保持约定格式；
            但标题、摘要、正文、主题、冲突、悬念、说明等自然语言字段必须是中文。
            """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public DeepSeekStagedScriptAiClient(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @Override
    public ChapterDigest generateChapterDigest(NovelProject project, NovelChapter chapter, GenerationOptions options) {
        String userPrompt = """
                任务：根据单章中文小说内容生成一个 ChapterDigest。
                输出要求：
                - 只返回一个 JSON 对象。
                - chapterIndex 必须与输入章节编号一致。
                - title 和 summary 必须是非空简体中文。
                - majorEvents / conflicts / openThreads / adaptationHints 必须是中文字符串数组。
                - characters 必须是 CharacterMention 对象数组。
                - locations 必须是 LocationMention 对象数组。
                - 如果不确定，可以返回空数组，但不要省略字段。
                - 所有自然语言字段都必须使用简体中文。
                输入上下文：
                %s
                """.formatted(toJson(new ChapterDigestPromptInput(
                project.getTitle(),
                chapter.getChapterIndex(),
                chapter.getTitle(),
                chapter.getContent(),
                options
        )));

        return callChapterDigest(project, chapter, userPrompt);
    }

    @Override
    public StoryBible generateStoryBible(NovelProject project, List<ChapterDigest> chapterDigests,
                                         GenerationOptions options) {
        String userPrompt = """
                任务：根据章节摘要生成一个 StoryBible。
                输出要求：
                - 只返回一个 JSON 对象。
                - characters 和 locations 必须是非空数组。
                - mainPlot 必须是非空简体中文。
                - themes 和 continuityRules 必须是中文字符串数组。
                - character id 必须使用 char_001 这种格式。
                - location id 必须使用 loc_001 这种格式。
                - role 可以使用结构化英文值，但人物名称、目标、特征、主线等自然语言内容必须是中文。
                输入上下文：
                %s
                """.formatted(toJson(new StoryBiblePromptInput(
                project.getTitle(),
                chapterDigests,
                options
        )));

        return callStoryBible(project, chapterDigests, userPrompt);
    }

    @Override
    public ScenePlan generateScenePlan(NovelProject project, List<ChapterDigest> chapterDigests, StoryBible storyBible,
                                       GenerationOptions options) {
        String userPrompt = """
                任务：根据章节摘要和设定集生成一个 ScenePlan。
                输出要求：
                - 只返回一个 JSON 对象。
                - scenes 必须是非空数组。
                - 每个 scene 都必须有非空的 id/title/locationId/timeOfDay/dramaticPurpose/summary。
                - 每个 scene 都必须有非空的 sourceChapters 和 characters 数组。
                - title、dramaticPurpose、summary、requiredBeats 都必须是简体中文。
                输入上下文：
                %s
                """.formatted(toJson(new ScenePlanPromptInput(
                project.getTitle(),
                chapterDigests,
                storyBible,
                options
        )));

        return callScenePlan(project, chapterDigests, storyBible, userPrompt);
    }

    @Override
    public SceneDraft generateSceneDraft(NovelProject project, SceneDraftContext context, GenerationOptions options) {
        String userPrompt = """
                任务：根据单场 SceneDraftContext 生成一个 SceneDraft。
                输出要求：
                - 只返回一个 JSON 对象。
                - id/sourceChapters/locationId/characters 必须与输入 plannedScene 保持一致。
                - title、dramaticPurpose、summary 必须是非空简体中文。
                - blocks 必须是非空数组。
                - 每个 block 都必须包含 type 和 text。
                - dialogue 类型的 block 必须使用输入 characters 中已有的 speakerId。
                - text 字段必须使用简体中文，不能写英文旁白。
                输入上下文：
                %s
                """.formatted(toJson(SceneDraftPromptInput.from(project, context, options)));

        return callSceneDraft(project, context, userPrompt);
    }

    private ChapterDigest callChapterDigest(NovelProject project, NovelChapter chapter, String userPrompt) {
        ObjectNode root = requestStageJson("chapter_digest", project, userPrompt);
        normalizeChapterDigest(root, chapter);
        return treeToValue("chapter_digest", root, ChapterDigest.class);
    }

    private StoryBible callStoryBible(NovelProject project, List<ChapterDigest> chapterDigests, String userPrompt) {
        ObjectNode root = requestStageJson("story_bible", project, userPrompt);
        normalizeStoryBible(root, chapterDigests);
        return treeToValue("story_bible", root, StoryBible.class);
    }

    private ScenePlan callScenePlan(NovelProject project, List<ChapterDigest> chapterDigests, StoryBible storyBible,
                                    String userPrompt) {
        ObjectNode root = requestStageJson("scene_plan", project, userPrompt);
        normalizeScenePlan(root, chapterDigests, storyBible);
        return treeToValue("scene_plan", root, ScenePlan.class);
    }

    private SceneDraft callSceneDraft(NovelProject project, SceneDraftContext context, String userPrompt) {
        ObjectNode root = requestStageJson("scene_draft", project, userPrompt);
        normalizeSceneDraft(root, context);
        return treeToValue("scene_draft", root, SceneDraft.class);
    }

    private ObjectNode requestStageJson(String stageName, NovelProject project, String userPrompt) {
        long startedAt = System.nanoTime();
        String content = chatClient.prompt()
                .system(STAGED_SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .content();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("DeepSeek staged response received projectId={} stage={} responseLength={} elapsedMs={}",
                project.getId(), stageName, content == null ? 0 : content.length(), elapsedMs);
        try {
            String json = extractJsonObject(content);
            JsonNode node = objectMapper.readTree(json);
            if (!(node instanceof ObjectNode objectNode)) {
                throw new IllegalStateException("阶段响应根节点必须是 JSON 对象");
            }
            return objectNode;
        } catch (Exception exception) {
            throw new IllegalStateException("DeepSeek staged response is not valid JSON for " + stageName, exception);
        }
    }

    private <T> T treeToValue(String stageName, ObjectNode root, Class<T> type) {
        try {
            return objectMapper.treeToValue(root, type);
        } catch (Exception exception) {
            throw new IllegalStateException("DeepSeek staged response is not valid " + stageName, exception);
        }
    }

    private void normalizeChapterDigest(ObjectNode root, NovelChapter chapter) {
        putIfBlank(root, "title", chapter.getTitle());
        putIfBlank(root, "summary", summarize(chapter.getContent(), 120));
        root.put("chapterIndex", chapter.getChapterIndex());

        ArrayNode majorEvents = ensureArray(root, "majorEvents");
        normalizeStringArray(majorEvents, "event");
        if (majorEvents.isEmpty()) {
            majorEvents.add("概括本章最关键的剧情转折。");
        }

        ArrayNode conflicts = ensureArray(root, "conflicts");
        ArrayNode openThreads = ensureArray(root, "openThreads");
        ArrayNode adaptationHints = ensureArray(root, "adaptationHints");
        normalizeStringArray(conflicts, "conflict");
        normalizeStringArray(openThreads, "open thread");
        normalizeStringArray(adaptationHints, "adaptation hint");
        if (adaptationHints.isEmpty()) {
            adaptationHints.add("保留本章最强的戏剧冲突并尽量外化呈现。");
        }

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
                putIfBlank(characterNode, "name", "人物" + (i + 1));
                ensureArray(characterNode, "aliases");
            }
        }

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
                putIfBlank(locationNode, "name", "地点" + (i + 1));
                putIfBlank(locationNode, "type", "interior");
            }
        }

        if (conflicts.isEmpty()) {
            conflicts.add("保留本章核心冲突。");
        }
        if (openThreads.isEmpty()) {
            openThreads.add("保留一个后续可延续的悬念。");
        }
    }

    private void normalizeStoryBible(ObjectNode root, List<ChapterDigest> chapterDigests) {
        putIfBlank(root, "mainPlot", buildMainPlotFallback(chapterDigests));
        ensureArray(root, "themes");
        ensureArray(root, "continuityRules");

        ArrayNode charactersNode = ensureArray(root, "characters");
        if (charactersNode.isEmpty()) {
            synthesizeCharacters(charactersNode, chapterDigests);
        } else {
            for (int i = 0; i < charactersNode.size(); i++) {
                JsonNode item = charactersNode.get(i);
                if (item instanceof ObjectNode characterNode) {
                    putIfBlank(characterNode, "id", "char_%03d".formatted(i + 1));
                    putIfBlank(characterNode, "name", "人物" + (i + 1));
                    putIfBlank(characterNode, "role", i == 0 ? "protagonist" : "supporting");
                    ensureArray(characterNode, "aliases");
                    ensureArray(characterNode, "traits");
                }
            }
        }

        ArrayNode locationsNode = ensureArray(root, "locations");
        if (locationsNode.isEmpty()) {
            synthesizeLocations(locationsNode, chapterDigests);
        } else {
            for (int i = 0; i < locationsNode.size(); i++) {
                JsonNode item = locationsNode.get(i);
                if (item instanceof ObjectNode locationNode) {
                    putIfBlank(locationNode, "id", "loc_%03d".formatted(i + 1));
                    putIfBlank(locationNode, "name", "地点" + (i + 1));
                    putIfBlank(locationNode, "type", "interior");
                }
            }
        }
    }

    private void normalizeScenePlan(ObjectNode root, List<ChapterDigest> chapterDigests, StoryBible storyBible) {
        ArrayNode scenesNode = ensureArray(root, "scenes");
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
                sceneNode.put("dramaticPurpose", "推动改编后的主要冲突继续升级。");
                sceneNode.put("summary", digest.summary());
                ArrayNode requiredBeats = sceneNode.putArray("requiredBeats");
                digest.majorEvents().forEach(requiredBeats::add);
                if (requiredBeats.isEmpty()) {
                    requiredBeats.add("保留本场最关键的戏剧节拍。");
                }
            }
        } else {
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
                    putIfBlank(sceneNode, "dramaticPurpose", "推动改编后的主要冲突继续升级。");
                    putIfBlank(sceneNode, "summary", digest.summary());
                    ArrayNode requiredBeats = ensureArray(sceneNode, "requiredBeats");
                    if (requiredBeats.isEmpty()) {
                        digest.majorEvents().forEach(requiredBeats::add);
                        if (requiredBeats.isEmpty()) {
                            requiredBeats.add("保留本场最关键的戏剧节拍。");
                        }
                    }
                }
            }
        }
    }

    private void normalizeSceneDraft(ObjectNode root, SceneDraftContext context) {
        PlannedScene scene = context.plannedScene();
        putIfBlank(root, "id", scene.id());
        putIfBlank(root, "title", scene.title());
        putIfBlank(root, "locationId", scene.locationId());
        putIfBlank(root, "timeOfDay", scene.timeOfDay());
        putIfBlank(root, "dramaticPurpose", scene.dramaticPurpose());
        putIfBlank(root, "summary", scene.summary());

        ArrayNode sourceChapters = ensureArray(root, "sourceChapters");
        if (sourceChapters.isEmpty()) {
            scene.sourceChapters().forEach(sourceChapters::add);
        }

        ArrayNode characters = ensureArray(root, "characters");
        if (characters.isEmpty()) {
            scene.characters().forEach(characters::add);
        }

        ArrayNode blocks = ensureArray(root, "blocks");
        if (blocks.isEmpty()) {
            ObjectNode action = blocks.addObject();
            action.put("type", "action");
            action.put("text", scene.summary());
            if (!scene.characters().isEmpty()) {
                ObjectNode dialogue = blocks.addObject();
                dialogue.put("type", "dialogue");
                dialogue.put("speakerId", scene.characters().getFirst());
                dialogue.put("text", "得继续查下去。");
            }
        } else {
            for (int i = 0; i < blocks.size(); i++) {
                JsonNode item = blocks.get(i);
                if (item instanceof ObjectNode blockNode) {
                    putIfBlank(blockNode, "type", "action");
                    putIfBlank(blockNode, "text", scene.summary());
                    if ("dialogue".equals(blockNode.path("type").asText())
                            && isBlank(blockNode.path("speakerId").asText())
                            && !scene.characters().isEmpty()) {
                        blockNode.put("speakerId", scene.characters().getFirst());
                    }
                }
            }
        }
    }

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
            character.put("goal", index == 1 ? "追查核心真相。" : "服务当前场景冲突。");
            character.putArray("traits");
            index++;
        }
    }

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
            location.put("type", "interior");
            index++;
        }
    }

    private String buildMainPlotFallback(List<ChapterDigest> chapterDigests) {
        String joined = chapterDigests.stream()
                .map(ChapterDigest::summary)
                .filter(summary -> summary != null && !summary.isBlank())
                .findFirst()
                .orElse("主角在不断逼近核心真相的过程中，被卷入更深层的冲突。");
        return summarize(joined, 160);
    }

    private ArrayNode ensureArray(ObjectNode node, String fieldName) {
        JsonNode current = node.get(fieldName);
        if (current instanceof ArrayNode arrayNode) {
            return arrayNode;
        }
        ArrayNode replacement = objectMapper.createArrayNode();
        node.set(fieldName, replacement);
        return replacement;
    }

    private void putIfBlank(ObjectNode node, String fieldName, String fallback) {
        JsonNode current = node.get(fieldName);
        if (current == null || current.isNull() || isBlank(current.asText())) {
            node.put(fieldName, fallback);
        }
    }

    private void normalizeStringArray(ArrayNode arrayNode, String fallbackLabel) {
        for (int i = 0; i < arrayNode.size(); i++) {
            JsonNode item = arrayNode.get(i);
            if (item == null || item.isNull()) {
                arrayNode.set(i, objectMapper.getNodeFactory().textNode(buildFallbackArrayText(fallbackLabel)));
                continue;
            }
            if (item.isTextual()) {
                continue;
            }
            if (item.isObject()) {
                String extracted = firstNonBlank(
                        item.path("text").asText(null),
                        item.path("summary").asText(null),
                        item.path("description").asText(null),
                        item.path("content").asText(null),
                        item.path("name").asText(null),
                        item.path("label").asText(null)
                );
                arrayNode.set(i, objectMapper.getNodeFactory().textNode(
                        extracted != null ? extracted : buildFallbackArrayText(fallbackLabel)
                ));
                continue;
            }
            arrayNode.set(i, objectMapper.getNodeFactory().textNode(item.asText()));
        }
    }

    private String buildFallbackArrayText(String fallbackLabel) {
        return switch (fallbackLabel) {
            case "event" -> "补充一个关键剧情事件。";
            case "conflict" -> "补充一个核心冲突点。";
            case "open thread" -> "补充一个可延续的悬念。";
            case "adaptation hint" -> "补充一个适合影视化呈现的改编提示。";
            default -> "补充一条必要信息。";
        };
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String summarize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "保留本章最强的戏剧节拍。";
        }
        String normalized = value.strip();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private String extractJsonObject(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("模型返回内容为空");
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("模型返回内容里找不到 JSON 对象");
        }
        return content.substring(start, end + 1);
    }

    private String toJson(Object value) {
        try {
            // Use stable JSON as prompt context so we do not lose required fields when building prompts.
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize staged prompt input", exception);
        }
    }

    private record ChapterDigestPromptInput(
            String projectTitle,
            int chapterIndex,
            String chapterTitle,
            String chapterContent,
            GenerationOptions options
    ) {
    }

    private record StoryBiblePromptInput(
            String projectTitle,
            List<ChapterDigest> chapterDigests,
            GenerationOptions options
    ) {
    }

    private record ScenePlanPromptInput(
            String projectTitle,
            List<ChapterDigest> chapterDigests,
            StoryBible storyBible,
            GenerationOptions options
    ) {
    }

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
