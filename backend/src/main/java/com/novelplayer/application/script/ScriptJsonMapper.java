package com.novelplayer.application.script;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelplayer.domain.script.ScriptDocument;
import org.springframework.stereotype.Component;

@Component
/**
 * 将权威剧本文档序列化为 JSON，便于持久化和后续审计。
 */
public class ScriptJsonMapper {

    private final ObjectMapper objectMapper;

    public ScriptJsonMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJson(ScriptDocument document) {
        try {
            return objectMapper.writeValueAsString(document);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize script document", exception);
        }
    }
}
