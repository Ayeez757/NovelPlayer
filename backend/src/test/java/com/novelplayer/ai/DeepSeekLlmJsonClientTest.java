package com.novelplayer.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekLlmJsonClientTest {

    private static final String SYSTEM_PROMPT = "Return JSON only.";
    private static final String USER_PROMPT = "Generate one chapter digest.";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void requestJsonExtractsObjectFromWrappedModelResponse() throws Exception {
        StubDeepSeekLlmJsonClient client = new StubDeepSeekLlmJsonClient(objectMapper);
        client.enqueueResponse("""
                I first considered another draft.
                ```json
                {
                  "chapterIndex": 1,
                  "title": "Rain Night",
                  "summary": "A letter changes everything.",
                  "majorEvents": [],
                  "characters": [],
                  "locations": [],
                  "conflicts": [],
                  "openThreads": [],
                  "adaptationHints": []
                }
                ```
                Final answer above.
                """);

        String json = client.requestJson("chapter_digest", SYSTEM_PROMPT, USER_PROMPT);

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.path("chapterIndex").asInt()).isEqualTo(1);
        assertThat(root.path("title").asText()).isEqualTo("Rain Night");
        assertThat(client.requestCount()).isEqualTo(1);
    }

    @Test
    void requestJsonRetriesWhenFirstResponseIsNotValidJson() throws Exception {
        StubDeepSeekLlmJsonClient client = new StubDeepSeekLlmJsonClient(objectMapper);
        client.enqueueResponse("not json at all");
        client.enqueueResponse("""
                {
                  "chapterIndex": 2,
                  "title": "Second Night",
                  "summary": "The clue becomes clearer.",
                  "majorEvents": [],
                  "characters": [],
                  "locations": [],
                  "conflicts": [],
                  "openThreads": [],
                  "adaptationHints": []
                }
                """);

        String json = client.requestJson("chapter_digest", SYSTEM_PROMPT, USER_PROMPT);

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.path("chapterIndex").asInt()).isEqualTo(2);
        assertThat(root.path("summary").asText()).contains("clue");
        assertThat(client.requestCount()).isEqualTo(2);
        assertThat(client.attempts()).containsExactly("initial", "retry");
    }

    private static final class StubDeepSeekLlmJsonClient extends DeepSeekLlmJsonClient {

        private final Queue<String> responses = new ArrayDeque<>();
        private final Queue<String> attempts = new ArrayDeque<>();
        private int requestCount;

        private StubDeepSeekLlmJsonClient(ObjectMapper objectMapper) {
            super(objectMapper);
        }

        private void enqueueResponse(String response) {
            responses.add(response);
        }

        private int requestCount() {
            return requestCount;
        }

        private String[] attempts() {
            return attempts.toArray(String[]::new);
        }

        @Override
        String requestStageContent(String stageName, String attempt, String systemPrompt, String userPrompt) {
            requestCount++;
            attempts.add(attempt);
            return responses.remove();
        }
    }
}
