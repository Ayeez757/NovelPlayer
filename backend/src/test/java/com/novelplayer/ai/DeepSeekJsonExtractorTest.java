package com.novelplayer.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepSeekJsonExtractorTest {

    private final DeepSeekJsonExtractor extractor = new DeepSeekJsonExtractor(new ObjectMapper());

    @Test
    void extractsStageObjectFromMarkdownAndReasoningNoise() {
        String content = """
                I first considered this scratch object: {"summary":"not the final answer"}.
                ```json
                {
                  "chapterIndex": 2,
                  "title": "Rain Letter",
                  "summary": "A letter changes the investigation.",
                  "majorEvents": ["A letter is found"],
                  "characters": [],
                  "locations": [],
                  "conflicts": [],
                  "openThreads": [],
                  "adaptationHints": []
                }
                ```
                Done.
                """;

        ObjectNode node = extractor.extractObject(content, "chapter_digest");

        assertThat(node.path("chapterIndex").asInt()).isEqualTo(2);
        assertThat(node.path("title").asText()).isEqualTo("Rain Letter");
    }

    @Test
    void recoversWhenMalformedBraceTextAppearsBeforeFinalJson() {
        String content = """
                Notes { this is not valid JSON and should be skipped.
                Final answer:
                {
                  "chapterIndex": 1,
                  "title": "First Night",
                  "summary": "The protagonist finds a clue.",
                  "majorEvents": [],
                  "characters": [],
                  "locations": [],
                  "conflicts": [],
                  "openThreads": [],
                  "adaptationHints": []
                }
                """;

        ObjectNode node = extractor.extractObject(content, "chapter_digest");

        assertThat(node.path("chapterIndex").asInt()).isEqualTo(1);
        assertThat(node.path("summary").asText()).contains("clue");
    }

    @Test
    void rejectsResponsesWithoutJsonObjects() {
        assertThatThrownBy(() -> extractor.extractObject("No JSON here.", "chapter_digest"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JSON object");
    }
}
