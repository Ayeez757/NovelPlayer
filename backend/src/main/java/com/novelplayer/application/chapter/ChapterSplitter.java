package com.novelplayer.application.chapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将作者粘贴的小说文本拆分成章节。
 *
 * 解析器优先识别常见的中英文章节标题；如果没有标题，则退化为按空行拆分，
 * 这样即便是粗略粘贴的文本也能完成演示流程。
 */
@Component
public class ChapterSplitter {

    private static final Logger log = LoggerFactory.getLogger(ChapterSplitter.class);

    /**
     * 支持中文章节标题，也兼容英文数字章节标题。
     */
    private static final Pattern CHAPTER_HEADING = Pattern.compile(
            "(?m)^\\s*((?:第\\s*[一二三四五六七八九十百千万零〇0-9]+\\s*[章节回卷集部篇].*)|(?:Chapter\\s+\\d+.*)|(?:CHAPTER\\s+\\d+.*))\\s*$"
    );

    /**
     * 拆分作者输入的小说原文。
     *
     * @param sourceText 作者粘贴的小说原文。
     * @return 已规范化编号的章节列表。
     */
    public List<ParsedChapter> split(String sourceText) {
        String normalized = sourceText == null ? "" : sourceText.replace("\r\n", "\n").trim();
        log.debug("Splitting source text sourceLength={}", normalized.length());
        if (normalized.isBlank()) {
            log.debug("Source text is blank; no chapters produced");
            return List.of();
        }

        Matcher matcher = CHAPTER_HEADING.matcher(normalized);
        List<Heading> headings = new ArrayList<>();
        while (matcher.find()) {
            // 保存标题在原文中的起止位置，后面按相邻标题之间的区间切出正文。
            headings.add(new Heading(matcher.start(), matcher.end(), matcher.group(1).trim()));
        }
        log.debug("Chapter headings detected headingCount={}", headings.size());

        // 没有章节标题的输入仍可用于演示，因此将段落粗略视为章节。
        if (headings.isEmpty()) {
            log.debug("No chapter heading detected; using fallback split");
            return fallbackSplit(normalized);
        }

        List<ParsedChapter> chapters = new ArrayList<>();
        for (int i = 0; i < headings.size(); i++) {
            Heading current = headings.get(i);
            int contentStart = current.end();
            int contentEnd = i + 1 < headings.size() ? headings.get(i + 1).start() : normalized.length();
            String content = normalized.substring(contentStart, contentEnd).trim();
            if (!content.isBlank()) {
                chapters.add(new ParsedChapter(chapters.size() + 1, current.title(), content));
            }
        }
        log.debug("Structured chapter split produced chapterCount={}", chapters.size());
        return chapters;
    }

    /**
     * 当文本没有明显章节标题时，按空行粗略切分为章节。
     *
     * @param text 已规范化换行的原文。
     * @return 退化拆分得到的章节列表。
     */
    private List<ParsedChapter> fallbackSplit(String text) {
        // 退化逻辑只按空行切块，不尝试猜测标题，降低误判成本。
        String[] chunks = text.split("\\n\\s*\\n");
        List<ParsedChapter> chapters = new ArrayList<>();
        for (String chunk : chunks) {
            String content = chunk.trim();
            if (!content.isBlank()) {
                chapters.add(new ParsedChapter(chapters.size() + 1, "第 " + (chapters.size() + 1) + " 章", content));
            }
        }
        log.debug("Fallback chapter split produced chapterCount={}", chapters.size());
        return chapters;
    }

    /**
     * 章节标题在原文中的定位信息。
     *
     * @param start 标题起始偏移。
     * @param end 标题结束偏移。
     * @param title 标题文本。
     */
    private record Heading(int start, int end, String title) {
    }
}
