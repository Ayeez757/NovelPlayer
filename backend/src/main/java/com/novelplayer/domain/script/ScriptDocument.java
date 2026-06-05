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
     */
    public record SceneBlock(
            @NotBlank String type,
            String speakerId,
            @NotBlank String text
    ) {
    }
}
