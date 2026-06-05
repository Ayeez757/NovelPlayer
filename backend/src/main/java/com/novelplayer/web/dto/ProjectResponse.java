package com.novelplayer.web.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 项目详情响应，包含项目状态和已识别章节。
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
