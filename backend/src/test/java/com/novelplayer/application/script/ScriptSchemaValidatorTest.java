package com.novelplayer.application.script;

import com.novelplayer.domain.script.ScriptDocument;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 覆盖跨引用校验，防止模型生成不存在的人物或地点编号。
 */
class ScriptSchemaValidatorTest {

    @Test
    void rejectsMissingCharacterReference() {
        ScriptDocument document = new ScriptDocument(
                "1.0",
                new ScriptDocument.ScriptMetadata("雨夜", "zh-CN", 3, OffsetDateTime.now()),
                new ScriptDocument.Adaptation("web_drama", "suspense", "一句话梗概", List.of()),
                List.of(new ScriptDocument.CharacterProfile("char_001", "林安", List.of(), "protagonist", null, List.of(), null)),
                List.of(new ScriptDocument.LocationProfile("loc_001", "旧书店", "interior", null)),
                List.of(new ScriptDocument.Scene(
                        "scene_001",
                        "雨夜",
                        List.of(1),
                        "loc_001",
                        "night",
                        List.of("char_999"),
                        "建立悬念",
                        "林安发现信件。",
                        List.of(new ScriptDocument.SceneBlock("dialogue", "char_999", "是谁留下的？"))
                )),
                List.of()
        );

        ScriptSchemaValidator validator = new ScriptSchemaValidator(Validation.buildDefaultValidatorFactory().getValidator());

        assertThatThrownBy(() -> validator.validate(document))
                .isInstanceOf(ScriptValidationException.class)
                .hasMessageContaining("不存在的人物");
    }
}
