package com.novelplayer.web.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 项目详情响应，包含项目状态和已识别章节。
 *
 * @param id 项目主键。
 * @param title 作品标题。
 * @param status 项目状态。
 * @param chapters 已识别章节摘要。
 * @param createdAt 创建时间。
 * @param updatedAt 最近更新时间。
 */
public record ProjectResponse(
        Long id,
        String title,
        String status,
        List<ChapterResponse> chapters,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
