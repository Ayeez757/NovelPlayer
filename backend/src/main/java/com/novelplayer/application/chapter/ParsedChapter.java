package com.novelplayer.application.chapter;

/**
 * 章节拆分后的轻量对象，进入持久化前先用它承载解析结果。
 */
public record ParsedChapter(int index, String title, String content) {
}
