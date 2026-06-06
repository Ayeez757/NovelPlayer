package com.novelplayer.web.dto;

/**
 * 章节识别结果摘要，用于前端展示章节列表。
 *
 * @param index 章节顺序，从 1 开始。
 * @param title 章节标题。
 * @param contentLength 章节正文长度。
 */
public record ChapterResponse(
        int index,
        String title,
        int contentLength
) {
}
