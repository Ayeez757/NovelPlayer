package com.novelplayer.application.chapter;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
/**
 * 将作者粘贴的小说文本拆分成章节。
 *
 * 解析器优先识别常见的中英文章节标题；如果没有标题，则退化为按空行拆分，
 * 这样即便是粗略粘贴的文本也能完成演示流程。
 */
public class ChapterSplitter {

    /**
     * 支持中文章节标题，也兼容英文数字章节标题。
     */
    private static final Pattern CHAPTER_HEADING = Pattern.compile(
            "(?m)^\\s*((?:第\\s*[一二三四五六七八九十百千万零〇0-9]+\\s*[章节回卷集部篇].*)|(?:Chapter\\s+\\d+.*)|(?:CHAPTER\\s+\\d+.*))\\s*$"
    );

    public List<ParsedChapter> split(String sourceText) {
        String normalized = sourceText == null ? "" : sourceText.replace("\r\n", "\n").trim();
        if (normalized.isBlank()) {
            return List.of();
        }

        Matcher matcher = CHAPTER_HEADING.matcher(normalized);
        List<Heading> headings = new ArrayList<>();
        while (matcher.find()) {
            headings.add(new Heading(matcher.start(), matcher.end(), matcher.group(1).trim()));
        }

        // 没有章节标题的输入仍可用于演示，因此将段落粗略视为章节。
        if (headings.isEmpty()) {
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
        return chapters;
    }

    private List<ParsedChapter> fallbackSplit(String text) {
        String[] chunks = text.split("\\n\\s*\\n");
        List<ParsedChapter> chapters = new ArrayList<>();
        for (String chunk : chunks) {
            String content = chunk.trim();
            if (!content.isBlank()) {
                chapters.add(new ParsedChapter(chapters.size() + 1, "第 " + (chapters.size() + 1) + " 章", content));
            }
        }
        return chapters;
    }

    private record Heading(int start, int end, String title) {
    }
}
