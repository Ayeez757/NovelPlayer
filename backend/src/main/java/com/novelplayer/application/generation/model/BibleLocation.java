package com.novelplayer.application.generation.model;

/**
 * 故事圣经阶段归并后的地点档案。
 *
 * <p>该结构属于生成中间产物，不直接复用最终 {@code ScriptDocument.LocationProfile}，
 * 后续由组装器负责转换为最终剧本文档的地点表。</p>
 *
 * @param id 稳定地点编号，例如 loc_001。
 * @param name 地点名称。
 * @param type 地点类型，例如 interior、exterior、virtual。
 * @param description 地点描述。
 */
public record BibleLocation(
        String id,
        String name,
        String type,
        String description
) {

    /**
     * 创建故事圣经地点档案，并规范化文本字段。
     */
    public BibleLocation {
        id = GenerationModelValidation.requireText(id, "id");
        name = GenerationModelValidation.requireText(name, "name");
        type = GenerationModelValidation.requireText(type, "type");
        description = GenerationModelValidation.optionalText(description);
    }
}
