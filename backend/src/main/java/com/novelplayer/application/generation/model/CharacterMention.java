package com.novelplayer.application.generation.model;

import java.util.List;

/**
 * 章节摘要阶段识别到的人物候选。
 *
 * @param name 原文中出现的人物名称。
 * @param aliases 同一人物在当前章节中的称呼或代词。
 * @param roleHint 人物在当前章节中的功能提示。
 * @param goalHint 人物在当前章节表现出的目标提示。
 */
public record CharacterMention(
        String name,
        List<String> aliases,
        String roleHint,
        String goalHint
) {

    /**
     * 创建人物候选，并规范化可选字段和列表。
     */
    public CharacterMention {
        name = GenerationModelValidation.requireText(name, "name");
        aliases = GenerationModelValidation.copyTextList(aliases);
        roleHint = GenerationModelValidation.optionalText(roleHint);
        goalHint = GenerationModelValidation.optionalText(goalHint);
    }
}
