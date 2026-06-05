package com.novelplayer.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 剧本生成参数，对应前端的改编设置表单。
 */
public record GenerationRequest(
        @NotBlank String format,
        @NotBlank String tone,
        @Min(0) @Max(100) int dialogueDensity,
        @Min(0) @Max(100) int narrationRetention
) {
}
