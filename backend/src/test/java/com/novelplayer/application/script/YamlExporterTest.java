package com.novelplayer.application.script;

import com.novelplayer.domain.script.ScriptDocument;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确认导出的 YAML 字段命名符合公开结构的下划线命名约定。
 */
class YamlExporterTest {

    /**
     * 验证 YAML 导出会把 Java 驼峰字段转换为下划线字段。
     */
    @Test
    void exportsSnakeCaseYaml() {
        ScriptDocument document = new ScriptDocument(
                "1.0",
                new ScriptDocument.ScriptMetadata("雨夜", "zh-CN", 3, OffsetDateTime.parse("2026-06-05T12:00:00+08:00")),
                new ScriptDocument.Adaptation("web_drama", "suspense", "一句话梗概", List.of("truth")),
                List.of(new ScriptDocument.CharacterProfile("char_001", "林安", List.of(), "protagonist", "找出真相", List.of("敏锐"), "克制")),
                List.of(new ScriptDocument.LocationProfile("loc_001", "旧书店", "interior", "昏暗")),
                List.of(new ScriptDocument.Scene(
                        "scene_001",
                        "雨夜",
                        List.of(1),
                        "loc_001",
                        "night",
                        List.of("char_001"),
                        "建立悬念",
                        "林安发现信件。",
                        List.of(new ScriptDocument.SceneBlock("dialogue", "char_001", "是谁留下的？"))
                )),
                List.of("测试备注")
        );

        String yaml = new YamlExporter().export(document);

        assertThat(yaml).contains("schema_version: \"1.0\"");
        assertThat(yaml).contains("source_chapter_count: 3");
        assertThat(yaml).contains("speaker_id: \"char_001\"");
    }
}
