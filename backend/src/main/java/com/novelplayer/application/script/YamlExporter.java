package com.novelplayer.application.script;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.novelplayer.domain.script.ScriptDocument;
import org.springframework.stereotype.Component;

@Component
/**
 * 将已校验的 Java 剧本文档导出为题目要求的 YAML 格式。
 */
public class YamlExporter {

    private final YAMLMapper yamlMapper;

    public YamlExporter() {
        yamlMapper = YAMLMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // YAML 的公开字段使用下划线命名，Java 数据对象字段保持驼峰命名。
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
    }

    public String export(ScriptDocument document) {
        try {
            return yamlMapper.writeValueAsString(document);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to export YAML", exception);
        }
    }
}
