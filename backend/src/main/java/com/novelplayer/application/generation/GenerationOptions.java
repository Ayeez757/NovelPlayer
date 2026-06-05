package com.novelplayer.application.generation;

import jakarta.validation.constraints.NotBlank;

/**
 * 前端传入的改编控制项，用于影响剧本类型、风格和叙事比例。
 */
public record GenerationOptions(
        @NotBlank String format,
        @NotBlank String tone,
        int dialogueDensity,
        int narrationRetention
) {

    public static GenerationOptions defaults() {
        return new GenerationOptions("web_drama", "suspense", 60, 30);
    }
}
