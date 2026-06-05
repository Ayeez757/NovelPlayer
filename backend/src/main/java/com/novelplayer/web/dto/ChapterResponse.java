package com.novelplayer.web.dto;

/**
 * 章节识别结果摘要，用于前端展示章节列表。
 */
public record ChapterResponse(
        int index,
        String title,
        int contentLength
) {
}
