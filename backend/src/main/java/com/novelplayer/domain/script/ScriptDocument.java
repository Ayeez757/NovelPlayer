package com.novelplayer.domain.script;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 内存中的权威剧本文档结构。
 *
 * JSON 持久化和 YAML 导出都以它为准，因此模型输出必须先映射并校验为该结构。
 *
 * @param schemaVersion 文档结构版本。
 * @param metadata 剧本元信息。
 * @param adaptation 改编意图。
 * @param characters 人物档案列表。
 * @param locations 地点档案列表。
 * @param scenes 场景列表。
 * @param revisionNotes 修订建议或生成备注。
 */
public record ScriptDocument(
        @NotBlank String schemaVersion,
        @NotNull @Valid ScriptMetadata metadata,
        @NotNull @Valid Adaptation adaptation,
        @NotEmpty List<@Valid CharacterProfile> characters,
        @NotEmpty List<@Valid LocationProfile> locations,
        @NotEmpty List<@Valid Scene> scenes,
        List<String> revisionNotes
) {

    /**
     * 剧本元信息，用于标识草稿来源、语言和生成时间。
     *
     * @param title 作品标题。
     * @param language 文档语言。
     * @param sourceChapterCount 原文章节数量。
     * @param generatedAt 生成时间。
     */
    public record ScriptMetadata(
            @NotBlank String title,
            @NotBlank String language,
            @Min(3) int sourceChapterCount,
            @NotNull OffsetDateTime generatedAt
    ) {
    }

    /**
     * 作者在生成前选择的改编意图。
     *
     * @param format 剧本形式。
     * @param tone 整体风格。
     * @param logline 故事一句话梗概。
     * @param themes 主题关键词。
     */
    public record Adaptation(
            @NotBlank String format,
            @NotBlank String tone,
            @NotBlank String logline,
            List<String> themes
    ) {
    }

    /**
     * 可复用角色档案，供场景和对白块引用。
     *
     * @param id 稳定人物编号。
     * @param name 人物姓名。
     * @param aliases 别名列表。
     * @param role 人物功能定位。
     * @param goal 人物目标。
     * @param traits 性格或行为特征。
     * @param voice 语言风格。
     */
    public record CharacterProfile(
            @NotBlank String id,
            @NotBlank String name,
            List<String> aliases,
            @NotBlank String role,
            String goal,
            List<String> traits,
            String voice
    ) {
    }

    /**
     * 可复用地点档案，供场景引用。
     *
     * @param id 稳定地点编号。
     * @param name 地点名称。
     * @param type 地点类型。
     * @param description 地点描述。
     */
    public record LocationProfile(
            @NotBlank String id,
            @NotBlank String name,
            @NotBlank String type,
            String description
    ) {
    }

    /**
     * 场景是剧本中最主要的可编辑单元。
     *
     * @param id 稳定场景编号。
     * @param title 场景标题。
     * @param sourceChapters 场景对应的原文章节编号。
     * @param locationId 引用的地点编号。
     * @param timeOfDay 场景时间。
     * @param characters 出场人物编号列表。
     * @param dramaticPurpose 戏剧目的。
     * @param summary 场景摘要。
     * @param blocks 场景内容块。
     */
    public record Scene(
            @NotBlank String id,
            @NotBlank String title,
            @NotEmpty List<Integer> sourceChapters,
            @NotBlank String locationId,
            @NotBlank String timeOfDay,
            @NotEmpty List<String> characters,
            @NotBlank String dramaticPurpose,
            @NotBlank String summary,
            @NotEmpty List<@Valid SceneBlock> blocks
    ) {
    }

    /**
     * 场景内容块，可表示动作、对白、转场或备注。
     *
     * @param type 内容块类型。
     * @param speakerId 对白块的说话人编号，非对白块可为空。
     * @param text 内容文本。
     */
    public record SceneBlock(
            @NotBlank String type,
            String speakerId,
            @NotBlank String text
    ) {
    }
}
