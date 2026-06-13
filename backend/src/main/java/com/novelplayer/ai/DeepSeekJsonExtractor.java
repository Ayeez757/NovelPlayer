package com.novelplayer.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.json.JsonReadFeature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DeepSeekJsonExtractor {

    private static final Map<String, Set<String>> STAGE_FIELDS = Map.of(
            "chapter_digest", Set.of(
                    "chapterIndex",
                    "title",
                    "summary",
                    "majorEvents",
                    "characters",
                    "locations",
                    "conflicts",
                    "openThreads",
                    "adaptationHints"
            ),
            "story_bible", Set.of(
                    "characters",
                    "locations",
                    "mainPlot",
                    "themes",
                    "continuityRules"
            ),
            "scene_plan", Set.of("scenes"),
            "scene_draft", Set.of(
                    "id",
                    "title",
                    "sourceChapters",
                    "locationId",
                    "timeOfDay",
                    "characters",
                    "dramaticPurpose",
                    "summary",
                    "blocks"
            )
    );

    private final ObjectMapper objectMapper;
    private final ObjectMapper lenientObjectMapper;

    DeepSeekJsonExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        /*
         * LLMs occasionally return almost-JSON even when the prompt asks for strict JSON:
         * single quotes, unquoted field names, comments, or a trailing comma in arrays/objects.
         * We still parse into JsonNode only, so a small, local lenient mapper improves recovery
         * without weakening the application's normal ObjectMapper configuration.
         */
        this.lenientObjectMapper = JsonMapper.builder()
                .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
                .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
                .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
                .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
                .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                .build();
    }

    ObjectNode extractObject(String content, String stageName) {
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("Model returned empty content");
        }

        List<JsonObjectCandidate> candidates = new ArrayList<>();
        for (int start = content.indexOf('{'); start >= 0; start = content.indexOf('{', start + 1)) {
            int end = findJsonObjectEnd(content, start);
            if (end < 0) {
                continue;
            }
            parseCandidate(content, start, end, stageName, candidates);
        }

        return candidates.stream()
                .max(Comparator.comparingInt(JsonObjectCandidate::score)
                        .thenComparingInt(JsonObjectCandidate::start))
                .map(JsonObjectCandidate::node)
                .orElseThrow(() -> new IllegalStateException("Model response does not contain a JSON object"));
    }

    private void parseCandidate(String content, int start, int end, String stageName,
                                List<JsonObjectCandidate> candidates) {
        String candidate = content.substring(start, end + 1);
        try {
            addCandidate(objectMapper.readTree(candidate), start, stageName, candidates);
        } catch (Exception ignored) {
            try {
                addCandidate(lenientObjectMapper.readTree(candidate), start, stageName, candidates);
            } catch (Exception ignoredAgain) {
                // DeepSeek can include explanatory snippets or malformed scratch JSON before the final object.
            }
        }
    }

    private void addCandidate(JsonNode node, int start, String stageName, List<JsonObjectCandidate> candidates) {
        if (node instanceof ObjectNode objectNode) {
            candidates.add(new JsonObjectCandidate(objectNode, start, score(stageName, objectNode)));
        }
    }

    private int findJsonObjectEnd(String content, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaping = false;
        for (int index = start; index < content.length(); index++) {
            char current = content.charAt(index);
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
            } else if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
                if (depth < 0) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private int score(String stageName, ObjectNode objectNode) {
        Set<String> expectedFields = STAGE_FIELDS.get(stageName);
        if (expectedFields == null || expectedFields.isEmpty()) {
            return 0;
        }

        int score = 0;
        for (String fieldName : expectedFields) {
            if (objectNode.has(fieldName)) {
                score++;
            }
        }
        return score;
    }

    private record JsonObjectCandidate(ObjectNode node, int start, int score) {
    }
}
