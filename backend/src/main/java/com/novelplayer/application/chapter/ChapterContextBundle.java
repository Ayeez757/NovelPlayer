package com.novelplayer.application.chapter;

import java.util.List;

/**
 * 章节上下文提取结果大礼包。
 *
 * 除了逐章摘要，也包含跨章节的聚合候选信息，便于提示词直接引用。
 */
public record ChapterContextBundle(
        List<ChapterContext> chapters,
        List<String> globalCharacterCandidates,
        List<String> globalLocationCandidates,
        List<String> globalConflictSignals
) {
}
