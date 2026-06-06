package com.novelplayer.application.generation;

import jakarta.validation.constraints.NotBlank;

/**
 * 前端传入的改编控制项，用于影响剧本类型、风格和叙事比例。
 *
 * @param format 剧本形式。
 * @param tone 整体风格。
 * @param dialogueDensity 对白密度。
 * @param narrationRetention 旁白保留度。
 * @param additionalInstructions 用户补充改编要求。
 */
public record GenerationOptions(
        @NotBlank String format,
        @NotBlank String tone,
        int dialogueDensity,
        int narrationRetention,
        String additionalInstructions
) {

    /**
     * 用户补充要求最大长度。控制自由文本进入模型上下文的成本和风险。
     */
    public static final int MAX_ADDITIONAL_INSTRUCTIONS_LENGTH = 4000;

    /**
     * 规范化并校验生成选项。
     */
    public GenerationOptions {
        // Web 层有参数校验；应用层仍做一次兜底，方便未来复用服务时保持边界稳定。
        format = normalizeRequired(format, "format");
        tone = normalizeRequired(tone, "tone");
        validateRange(dialogueDensity, "dialogueDensity");
        validateRange(narrationRetention, "narrationRetention");
        additionalInstructions = normalizeAdditionalInstructions(additionalInstructions);
    }

    /**
     * 构建默认生成选项。
     *
     * @return 默认生成选项。
     */
    public static GenerationOptions defaults() {
        return new GenerationOptions("web_drama", "suspense", 60, 30, null);
    }

    /**
     * 用户是否提供了额外改编要求。
     *
     * @return true 表示存在可用补充要求。
     */
    public boolean hasAdditionalInstructions() {
        return additionalInstructions != null && !additionalInstructions.isBlank();
    }

    private static String normalizeRequired(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }

    private static void validateRange(int value, String name) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException(name + " must be between 0 and 100");
        }
    }

    private static String normalizeAdditionalInstructions(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        // 统一换行，避免同一提示词在不同客户端提交时产生不必要的差异。
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (normalized.length() > MAX_ADDITIONAL_INSTRUCTIONS_LENGTH) {
            throw new IllegalArgumentException("additionalInstructions must be at most "
                    + MAX_ADDITIONAL_INSTRUCTIONS_LENGTH + " characters");
        }

        StringBuilder builder = new StringBuilder(normalized.length());
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            // 保留正常文本、换行和制表符，过滤不可见控制字符，减少日志和提示词污染。
            if (character == '\n' || character == '\t' || character >= 0x20) {
                builder.append(character);
            }
        }
        String sanitized = builder.toString().strip();
        return sanitized.isBlank() ? null : sanitized;
    }
}
