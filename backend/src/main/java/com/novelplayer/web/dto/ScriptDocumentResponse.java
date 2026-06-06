package com.novelplayer.web.dto;

import java.time.OffsetDateTime;

/**
 * 剧本文档响应，当前主要返回最新 YAML 内容和校验状态。
 *
 * @param id 剧本文档主键。
 * @param projectId 所属项目主键。
 * @param schemaVersion 剧本文档结构版本。
 * @param validationStatus 结构校验状态。
 * @param yamlContent 可编辑或下载的 YAML 内容。
 * @param createdAt 文档创建时间。
 */
public record ScriptDocumentResponse(
        Long id,
        Long projectId,
        String schemaVersion,
        String validationStatus,
        String yamlContent,
        OffsetDateTime createdAt
) {
}
