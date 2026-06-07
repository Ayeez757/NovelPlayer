package com.novelplayer.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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

    DeepSeekJsonExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
            JsonNode node = objectMapper.readTree(candidate);
            if (node instanceof ObjectNode objectNode) {
                candidates.add(new JsonObjectCandidate(objectNode, start, score(stageName, objectNode)));
            }
        } catch (Exception ignored) {
            // DeepSeek can include explanatory snippets or malformed scratch JSON before the final object.
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
