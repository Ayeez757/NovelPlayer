package com.novelplayer.application.generation.model;

import java.util.List;

/**
 * 单章小说压缩理解后的结构化摘要。
 *
 * <p>该模型只表达章节级素材，不直接包含最终剧本文档字段，后续会先进入故事圣经和场景规划阶段。</p>
 *
 * @param chapterIndex 章节序号，从 1 开始。
 * @param title 章节标题。
 * @param summary 章节摘要。
 * @param majorEvents 当前章节的主要事件。
 * @param characters 当前章节识别到的人物候选。
 * @param locations 当前章节识别到的地点候选。
 * @param conflicts 当前章节的冲突或张力点。
 * @param openThreads 当前章节留下的伏笔、悬念或待解决问题。
 * @param adaptationHints 当前章节改编成剧本时可参考的提示。
 */
public record ChapterDigest(
        int chapterIndex,
        String title,
        String summary,
        List<String> majorEvents,
        List<CharacterMention> characters,
        List<LocationMention> locations,
        List<String> conflicts,
        List<String> openThreads,
        List<String> adaptationHints
) {

    /**
     * 创建章节摘要，并对文本和列表做轻量规范化。
     */
    public ChapterDigest {
        chapterIndex = GenerationModelValidation.requirePositive(chapterIndex, "chapterIndex");
        title = GenerationModelValidation.requireText(title, "title");
        summary = GenerationModelValidation.requireText(summary, "summary");
        majorEvents = GenerationModelValidation.copyTextList(majorEvents);
        characters = GenerationModelValidation.copyList(characters, "characters");
        locations = GenerationModelValidation.copyList(locations, "locations");
        conflicts = GenerationModelValidation.copyTextList(conflicts);
        openThreads = GenerationModelValidation.copyTextList(openThreads);
        adaptationHints = GenerationModelValidation.copyTextList(adaptationHints);
    }
}
