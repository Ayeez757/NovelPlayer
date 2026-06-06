package com.novelplayer.application.generation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 覆盖自由文本生成选项的规范化，避免异常字符或过长内容进入模型上下文。
 */
class GenerationOptionsTest {

    /**
     * 验证生成选项会去除首尾空白、统一换行并过滤控制字符。
     */
    @Test
    void normalizesAdditionalInstructions() {
        GenerationOptions options = new GenerationOptions(" web_drama ", " suspense ", 60, 30,
                "  保留反转\r\n减少旁白\u0007  ");

        assertThat(options.format()).isEqualTo("web_drama");
        assertThat(options.tone()).isEqualTo("suspense");
        assertThat(options.additionalInstructions()).isEqualTo("保留反转\n减少旁白");
        assertThat(options.hasAdditionalInstructions()).isTrue();
    }

    /**
     * 验证空白补充要求会被视为未填写。
     */
    @Test
    void treatsBlankAdditionalInstructionsAsAbsent() {
        GenerationOptions options = new GenerationOptions("web_drama", "suspense", 60, 30, " \n\t ");

        assertThat(options.additionalInstructions()).isNull();
        assertThat(options.hasAdditionalInstructions()).isFalse();
    }

    /**
     * 验证比例参数和补充要求长度会被应用层兜底校验。
     */
    @Test
    void rejectsInvalidRangesAndOverlongInstructions() {
        String tooLong = "a".repeat(GenerationOptions.MAX_ADDITIONAL_INSTRUCTIONS_LENGTH + 1);

        assertThatThrownBy(() -> new GenerationOptions("web_drama", "suspense", 101, 30, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dialogueDensity");
        assertThatThrownBy(() -> new GenerationOptions("web_drama", "suspense", 60, 30, tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("additionalInstructions");
    }
}
