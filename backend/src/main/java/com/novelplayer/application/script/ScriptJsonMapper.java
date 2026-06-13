package com.novelplayer.application.script;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelplayer.domain.script.ScriptDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 将权威剧本文档序列化为 JSON，便于持久化和后续审计。
 */
@Component
public class ScriptJsonMapper {

    private static final Logger log = LoggerFactory.getLogger(ScriptJsonMapper.class);

    private final ObjectMapper objectMapper;

    /**
     * 注入 JSON 序列化器。
     *
     * @param objectMapper Spring Boot 配置好的 Jackson ObjectMapper。
     */
    public ScriptJsonMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将剧本文档转为 JSON 字符串。
     *
     * @param document 剧本文档。
     * @return JSON 文本。
     */
    public String toJson(ScriptDocument document) {
        try {
            String json = objectMapper.writeValueAsString(document);
            log.debug("Script document serialized 剧本文档json序列化完成。schemaVersion={} jsonLength={}",
                    document.schemaVersion(), json.length());
            return json;
        } catch (Exception exception) {
            log.warn("Script document serialization failed 剧本文档json序列化失败。schemaVersion={}", document.schemaVersion(), exception);
            throw new IllegalStateException("Failed to serialize script document", exception);
        }
    }
}
