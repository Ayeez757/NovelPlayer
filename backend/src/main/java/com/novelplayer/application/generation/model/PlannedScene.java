package com.novelplayer.application.generation.model;

import java.util.List;

/**
 * 场景规划阶段产出的单个场景大纲。
 *
 * <p>场景大纲只决定改编结构和必备戏剧节点，不承载最终动作、对白等正文块。</p>
 *
 * @param id 稳定场景编号，例如 scene_001。
 * @param title 场景标题。
 * @param sourceChapters 场景对应的原文章节编号。
 * @param locationId 场景引用的地点编号。
 * @param timeOfDay 场景时间提示。
 * @param characters 场景出场人物编号列表。
 * @param dramaticPurpose 场景在剧作结构中的戏剧目的。
 * @param summary 场景摘要。
 * @param requiredBeats 场景必须覆盖的关键节拍。
 */
public record PlannedScene(
        String id,
        String title,
        List<Integer> sourceChapters,
        String locationId,
        String timeOfDay,
        List<String> characters,
        String dramaticPurpose,
        String summary,
        List<String> requiredBeats
) {

    /**
     * 创建场景大纲，并校验后续分场生成所需的关键引用。
     */
    public PlannedScene {
        id = GenerationModelValidation.requireText(id, "id");
        title = GenerationModelValidation.requireText(title, "title");
        sourceChapters = GenerationModelValidation.requirePositiveIntegerList(sourceChapters, "sourceChapters");
        locationId = GenerationModelValidation.requireText(locationId, "locationId");
        timeOfDay = GenerationModelValidation.requireText(timeOfDay, "timeOfDay");
        characters = GenerationModelValidation.requireTextList(characters, "characters");
        dramaticPurpose = GenerationModelValidation.requireText(dramaticPurpose, "dramaticPurpose");
        summary = GenerationModelValidation.requireText(summary, "summary");
        requiredBeats = GenerationModelValidation.copyTextList(requiredBeats);
    }
}
