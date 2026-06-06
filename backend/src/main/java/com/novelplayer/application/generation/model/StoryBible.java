package com.novelplayer.application.generation.model;

import java.util.List;

/**
 * 面向长篇改编的全局故事圣经。
 *
 * <p>故事圣经用于统一人物、地点、主线、主题和连续性规则，供后续场景规划与分场写作引用。
 * 它是生成过程中的创作资产，不等同于最终 YAML 文档。</p>
 *
 * @param characters 稳定人物档案列表。
 * @param locations 稳定地点档案列表。
 * @param mainPlot 主线剧情概述。
 * @param themes 主题关键词。
 * @param continuityRules 连续性规则和不可提前泄露的信息。
 */
public record StoryBible(
        List<BibleCharacter> characters,
        List<BibleLocation> locations,
        String mainPlot,
        List<String> themes,
        List<String> continuityRules
) {

    /**
     * 创建故事圣经，并要求人物、地点和主线具备最小可用信息。
     */
    public StoryBible {
        characters = GenerationModelValidation.requireList(characters, "characters");
        locations = GenerationModelValidation.requireList(locations, "locations");
        mainPlot = GenerationModelValidation.requireText(mainPlot, "mainPlot");
        themes = GenerationModelValidation.copyTextList(themes);
        continuityRules = GenerationModelValidation.copyTextList(continuityRules);
    }
}
