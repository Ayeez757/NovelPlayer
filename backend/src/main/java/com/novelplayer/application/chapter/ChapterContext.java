package com.novelplayer.application.chapter;

import java.util.List;

/**
 * 供提示词使用的章节上下文摘要。
 *
 * 不是最终持久化模型，只是把原始章节压缩成更适合喂给大模型的结构化信息。
 */
public record ChapterContext(
        int chapterIndex,
        String title,
        String summary,
        String openingHook,
        String endingHook,
        List<String> characterCandidates,
        List<String> locationCandidates,
        List<String> conflictSignals,
        String excerpt
) {
}
