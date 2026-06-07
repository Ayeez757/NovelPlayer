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
            You are a structured fiction adaptation assistant.
            Return JSON only.
            Do not output markdown fences, explanations, or extra prose.
            Every required field must be present and non-blank.
            Reuse only ids and references that already exist in the input context.
            """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final DeepSeekJsonExtractor jsonExtractor;

    public DeepSeekStagedScriptAiClient(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
        this.jsonExtractor = new DeepSeekJsonExtractor(objectMapper);
    }

    @Override
    public ChapterDigest generateChapterDigest(NovelProject project, NovelChapter chapter, GenerationOptions options) {
        String userPrompt = """
                Task: generate one ChapterDigest from a single novel chapter.
                Output rules:
                - Return one JSON object only.
                - chapterIndex must equal the input chapterIndex.
                - title and summary must be non-blank.
                - majorEvents / characters / locations / conflicts / openThreads / adaptationHints must be arrays.
                - If uncertain, keep arrays empty instead of omitting them.
                Input:
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
                Task: generate one StoryBible from chapter digests.
                Output rules:
                - Return one JSON object only.
                - characters and locations must be non-empty arrays.
                - mainPlot must be non-blank.
                - character ids must use char_001 style.
                - location ids must use loc_001 style.
                Input:
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
                Task: generate one ScenePlan from chapter digests and story bible.
                Output rules:
                - Return one JSON object only.
                - scenes must be a non-empty array.
                - Each scene must have non-blank id/title/locationId/timeOfDay/dramaticPurpose/summary.
                - Each scene must have non-empty sourceChapters and characters arrays.
                Input:
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
                Task: generate one SceneDraft from SceneDraftContext.
                Output rules:
                - Return one JSON object only.
                - Keep id/sourceChapters/locationId/characters aligned with the input plannedScene.
                - title, dramaticPurpose and summary must be non-blank.
                - blocks must be a non-empty array.
                - Every block must contain type and text.
                - Dialogue blocks should use a speakerId from the input characters.
                Input:
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
            return jsonExtractor.extractObject(content, stageName);
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
            majorEvents.add("Summarize the chapter's main turning point.");
        }

        ArrayNode conflicts = ensureArray(root, "conflicts");
        ArrayNode openThreads = ensureArray(root, "openThreads");
        ArrayNode adaptationHints = ensureArray(root, "adaptationHints");
        normalizeStringArray(conflicts, "conflict");
        normalizeStringArray(openThreads, "open thread");
        normalizeStringArray(adaptationHints, "adaptation hint");
        if (adaptationHints.isEmpty()) {
            adaptationHints.add("Keep the strongest dramatic beat visible on screen.");
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
                putIfBlank(characterNode, "name", "Character " + (i + 1));
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
                putIfBlank(locationNode, "name", "Location " + (i + 1));
                putIfBlank(locationNode, "type", "interior");
            }
        }

        if (conflicts.isEmpty()) {
            conflicts.add("Retain the chapter's core conflict in the adaptation.");
        }
        if (openThreads.isEmpty()) {
            openThreads.add("Carry one unresolved hook into later scenes.");
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
                    putIfBlank(characterNode, "name", "Character " + (i + 1));
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
                    putIfBlank(locationNode, "name", "Location " + (i + 1));
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
                sceneNode.put("dramaticPurpose", "Move the adaptation conflict forward.");
                sceneNode.put("summary", digest.summary());
                ArrayNode requiredBeats = sceneNode.putArray("requiredBeats");
                digest.majorEvents().forEach(requiredBeats::add);
                if (requiredBeats.isEmpty()) {
                    requiredBeats.add("Retain the core dramatic beat.");
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
                    putIfBlank(sceneNode, "dramaticPurpose", "Move the adaptation conflict forward.");
                    putIfBlank(sceneNode, "summary", digest.summary());
                    ArrayNode requiredBeats = ensureArray(sceneNode, "requiredBeats");
                    if (requiredBeats.isEmpty()) {
                        digest.majorEvents().forEach(requiredBeats::add);
                        if (requiredBeats.isEmpty()) {
                            requiredBeats.add("Retain the core dramatic beat.");
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
                dialogue.put("text", "We have to keep moving.");
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

    private String buildMainPlotFallback(List<ChapterDigest> chapterDigests) {
        String joined = chapterDigests.stream()
                .map(ChapterDigest::summary)
                .filter(summary -> summary != null && !summary.isBlank())
                .findFirst()
                .orElse("A protagonist uncovers the core conflict and keeps moving toward the truth.");
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
                arrayNode.set(i, objectMapper.getNodeFactory().textNode("Keep one " + fallbackLabel + " visible."));
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
                        extracted != null ? extracted : "Keep one " + fallbackLabel + " visible."
                ));
                continue;
            }
            arrayNode.set(i, objectMapper.getNodeFactory().textNode(item.asText()));
        }
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
            return "Preserve the chapter's strongest dramatic beat.";
        }
        String normalized = value.strip();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
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
