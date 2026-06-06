package com.novelplayer.application.chapter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖章节拆分的两条关键路径：显式标题和无标题段落兜底。
 */
class ChapterSplitterTest {

    private final ChapterSplitter splitter = new ChapterSplitter();

    /**
     * 验证中文章节标题能被识别并按标题切分正文。
     */
    @Test
    void splitsChineseChapterHeadings() {
        String text = """
                第一章 雨夜
                她推开门。

                第二章 缺页
                书少了一页。

                第三章 交易
                他提出交换。
                """;

        List<ParsedChapter> chapters = splitter.split(text);

        assertThat(chapters).hasSize(3);
        assertThat(chapters.get(0).title()).isEqualTo("第一章 雨夜");
        assertThat(chapters.get(1).content()).contains("书少了一页");
    }

    /**
     * 验证没有章节标题时会按空行退化切分。
     */
    @Test
    void fallsBackToBlankLineChunks() {
        String text = """
                第一段内容。

                第二段内容。

                第三段内容。
                """;

        List<ParsedChapter> chapters = splitter.split(text);

        assertThat(chapters).hasSize(3);
        assertThat(chapters.get(0).title()).isEqualTo("第 1 章");
    }
}
