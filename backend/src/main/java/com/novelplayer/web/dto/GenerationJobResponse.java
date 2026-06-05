package com.novelplayer.web.dto;

import java.time.OffsetDateTime;

/**
 * 生成任务状态响应，预留给后续异步进度查询或服务端事件推送。
 */
public record GenerationJobResponse(
        Long id,
        Long projectId,
        String status,
        String currentStage,
        String errorMessage,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt
) {
}
