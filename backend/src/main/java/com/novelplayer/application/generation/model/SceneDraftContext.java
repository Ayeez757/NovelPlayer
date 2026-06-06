package com.novelplayer.application.generation.model;

import com.novelplayer.domain.project.NovelChapter;

import java.util.List;
import java.util.Objects;

/**
 * 分场写作阶段传给 AI 的最小上下文。
 *
 * <p>该结构刻意不携带完整故事圣经，只包含当前场景真正需要的原文章节、人物、地点、
 * 连续性规则和前一场摘要，避免分场生成阶段重新退化成长上下文的一次性生成。</p>
 *
 * @param plannedScene 当前待写作的场景规划。
 * @param sourceChapters 当前场景引用的原文章节正文。
 * @param characters 当前场景涉及的人物资料。
 * @param location 当前场景涉及的地点资料。
 * @param continuityRules 全局连续性规则。
 * @param previousSceneSummary 前一个场景摘要，可为空。
 */
public record SceneDraftContext(
        PlannedScene plannedScene,
        List<NovelChapter> sourceChapters,
        List<BibleCharacter> characters,
        BibleLocation location,
        List<String> continuityRules,
        String previousSceneSummary
) {

    /**
     * 创建分场写作上下文，并确保 AI 输入只包含可用的最小素材。
     */
    public SceneDraftContext {
        plannedScene = Objects.requireNonNull(plannedScene, "plannedScene must not be null");
        sourceChapters = GenerationModelValidation.requireList(sourceChapters, "sourceChapters");
        characters = GenerationModelValidation.requireList(characters, "characters");
        location = Objects.requireNonNull(location, "location must not be null");
        continuityRules = GenerationModelValidation.copyTextList(continuityRules);
        previousSceneSummary = GenerationModelValidation.optionalText(previousSceneSummary);
    }
}
