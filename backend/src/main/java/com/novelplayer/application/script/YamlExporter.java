package com.novelplayer.application.script;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.novelplayer.domain.script.ScriptDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 将已校验的 Java 剧本文档导出为题目要求的 YAML 格式。
 */
@Component
public class YamlExporter {

    private static final Logger log = LoggerFactory.getLogger(YamlExporter.class);

    private final YAMLMapper yamlMapper;

    /**
     * 初始化 YAML 序列化器。
     */
    public YamlExporter() {
        // Mapper 无运行时状态，可作为组件复用；命名策略在这里集中定义。
        yamlMapper = YAMLMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // YAML 的公开字段使用下划线命名，Java 数据对象字段保持驼峰命名。
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
    }

    /**
     * 将已校验剧本文档导出为 YAML 字符串。
     *
     * @param document 剧本文档。
     * @return YAML 文本。
     */
    public String export(ScriptDocument document) {
        try {
            log.debug("Exporting script document to YAML schemaVersion={} sceneCount={}",
                    document.schemaVersion(), document.scenes().size());
            String yaml = yamlMapper.writeValueAsString(document);
            log.debug("YAML export completed schemaVersion={} yamlLength={}", document.schemaVersion(), yaml.length());
            return yaml;
        } catch (Exception exception) {
            log.warn("YAML export failed schemaVersion={}", document.schemaVersion(), exception);
            throw new IllegalStateException("Failed to export YAML", exception);
        }
    }
}
