package com.novelplayer.application.chapter;

/**
 * 章节拆分后的轻量对象，进入持久化前先用它承载解析结果。
 *
 * @param index 章节顺序，从 1 开始。
 * @param title 章节标题。
 * @param content 章节正文。
 */
public record ParsedChapter(int index, String title, String content) {
}
