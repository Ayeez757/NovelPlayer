package com.novelplayer.application.generation.model;

import java.util.List;

/**
 * 分场写作阶段产出的单个场景草稿。
 *
 * <p>它是场景规划落到具体动作、对白和转场后的中间结果，仍然不直接等同于最终 {@code ScriptDocument.Scene}。</p>
 *
 * @param id 稳定场景编号。
 * @param title 场景标题。
 * @param sourceChapters 场景对应的原文章节编号。
 * @param locationId 场景引用的地点编号。
 * @param timeOfDay 场景时间提示。
 * @param characters 场景出场人物编号列表。
 * @param dramaticPurpose 场景戏剧目的。
 * @param summary 场景摘要。
 * @param blocks 场景正文内容块。
 */
public record SceneDraft(
        String id,
        String title,
        List<Integer> sourceChapters,
        String locationId,
        String timeOfDay,
        List<String> characters,
        String dramaticPurpose,
        String summary,
        List<DraftSceneBlock> blocks
) {

    /**
     * 创建分场草稿，并校验最终组装所需的关键字段。
     */
    public SceneDraft {
        id = GenerationModelValidation.requireText(id, "id");
        title = GenerationModelValidation.requireText(title, "title");
        sourceChapters = GenerationModelValidation.requirePositiveIntegerList(sourceChapters, "sourceChapters");
        locationId = GenerationModelValidation.requireText(locationId, "locationId");
        timeOfDay = GenerationModelValidation.requireText(timeOfDay, "timeOfDay");
        characters = GenerationModelValidation.requireTextList(characters, "characters");
        dramaticPurpose = GenerationModelValidation.requireText(dramaticPurpose, "dramaticPurpose");
        summary = GenerationModelValidation.requireText(summary, "summary");
        blocks = GenerationModelValidation.requireList(blocks, "blocks");
    }
}
