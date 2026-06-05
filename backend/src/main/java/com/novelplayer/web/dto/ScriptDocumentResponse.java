package com.novelplayer.web.dto;

import java.time.OffsetDateTime;

/**
 * 剧本文档响应，当前主要返回最新 YAML 内容和校验状态。
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
