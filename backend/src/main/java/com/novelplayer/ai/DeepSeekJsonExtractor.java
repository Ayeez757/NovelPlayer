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
LLM即便在提示词要求严格输出标准 JSON 时，偶尔仍会返回近似 JSON 的非标准格式：
比如使用单引号、字段名不带双引号、内容带注释、数组 / 对象末尾存在多余逗号等情况。
我们这里仅需要将返回内容解析为JsonNode节点对象，因此引入一个轻量、局部宽松模式的解析器，既能提升异常内容的兼容容错能力，又不会破坏应用全局默认ObjectMapper的标准严格配置。
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
