package com.novelplayer.application.generation.model;

import java.util.List;

/**
 * 故事圣经阶段归并后的人物档案。
 *
 * <p>该结构属于生成中间产物，不直接复用最终 {@code ScriptDocument.CharacterProfile}，
 * 后续由组装器负责转换为最终剧本文档的人物表。</p>
 *
 * @param id 稳定人物编号，例如 char_001。
 * @param name 人物主名称。
 * @param aliases 人物别名或代称。
 * @param role 人物功能定位。
 * @param goal 人物核心目标。
 * @param traits 人物性格或行为特征。
 * @param voice 人物对白风格提示。
 */
public record BibleCharacter(
        String id,
        String name,
        List<String> aliases,
        String role,
        String goal,
        List<String> traits,
        String voice
) {

    /**
     * 创建故事圣经人物档案，并规范化文本和列表字段。
     */
    public BibleCharacter {
        id = GenerationModelValidation.requireText(id, "id");
        name = GenerationModelValidation.requireText(name, "name");
        aliases = GenerationModelValidation.copyTextList(aliases);
        role = GenerationModelValidation.requireText(role, "role");
        goal = GenerationModelValidation.optionalText(goal);
        traits = GenerationModelValidation.copyTextList(traits);
        voice = GenerationModelValidation.optionalText(voice);
    }
}
