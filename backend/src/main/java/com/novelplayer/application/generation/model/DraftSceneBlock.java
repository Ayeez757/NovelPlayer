package com.novelplayer.application.generation.model;

/**
 * 分场草稿中的内容块。
 *
 * <p>该结构用于保存模型生成的场景正文草稿，后续再由组装器映射为最终剧本文档块。</p>
 *
 * @param type 内容块类型，例如 action、dialogue、transition、note。
 * @param speakerId 对白块的说话人编号，非对白块可为空。
 * @param text 内容文本。
 */
public record DraftSceneBlock(
        String type,
        String speakerId,
        String text
) {

    /**
     * 创建分场草稿内容块，并规范化文本字段。
     */
    public DraftSceneBlock {
        type = GenerationModelValidation.requireText(type, "type");
        speakerId = GenerationModelValidation.optionalText(speakerId);
        text = GenerationModelValidation.requireText(text, "text");
    }
}
