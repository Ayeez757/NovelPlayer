package com.novelplayer.application.generation.model;

/**
 * 章节摘要阶段识别到的地点候选。
 *
 * @param name 原文中出现的地点名称。
 * @param type 地点类型提示，例如 interior、exterior、virtual。
 * @param description 当前章节中对地点的简要描述。
 */
public record LocationMention(
        String name,
        String type,
        String description
) {

    /**
     * 创建地点候选，并规范化文本字段。
     */
    public LocationMention {
        name = GenerationModelValidation.requireText(name, "name");
        type = GenerationModelValidation.optionalText(type);
        description = GenerationModelValidation.optionalText(description);
    }
}
