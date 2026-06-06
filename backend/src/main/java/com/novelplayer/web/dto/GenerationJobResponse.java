package com.novelplayer.web.dto;

import java.time.OffsetDateTime;

/**
 * 生成任务状态响应，预留给后续异步进度查询或服务端事件推送。
 *
 * @param id 生成任务主键。
 * @param projectId 所属项目主键。
 * @param status 任务状态。
 * @param currentStage 当前阶段名称。
 * @param errorMessage 失败时的错误信息。
 * @param createdAt 任务创建时间。
 * @param finishedAt 任务结束时间，未完成时为空。
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
