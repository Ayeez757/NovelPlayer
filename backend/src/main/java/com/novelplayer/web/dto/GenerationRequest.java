package com.novelplayer.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 剧本生成参数，对应前端的改编设置表单。
 *
 * @param format 剧本形式，例如短剧或影视分场。
 * @param tone 整体风格。
 * @param dialogueDensity 对白密度，取值 0 到 100。
 * @param narrationRetention 旁白保留度，取值 0 到 100。
 * @param additionalInstructions 用户补充改编要求。
 */
public record GenerationRequest(
        @NotBlank String format,
        @NotBlank String tone,
        @Min(0) @Max(100) int dialogueDensity,
        @Min(0) @Max(100) int narrationRetention,
        @Size(max = 4000, message = "must be at most 4000 characters")
        String additionalInstructions
) {
}
