package com.novelplayer.domain.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 从作者粘贴原文中提取出的规范化章节。
 */
@Entity
@Table(name = "novel_chapter")
public class NovelChapter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private NovelProject project;

    @Column(name = "chapter_index", nullable = false)
    private int chapterIndex;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    /**
     * JPA 反射创建实体时使用。
     */
    protected NovelChapter() {
    }

    /**
     * 创建已规范化的小说章节实体。
     *
     * @param project 所属项目。
     * @param chapterIndex 章节顺序，从 1 开始。
     * @param title 章节标题。
     * @param content 章节正文。
     */
    public NovelChapter(NovelProject project, int chapterIndex, String title, String content) {
        this.project = project;
        this.chapterIndex = chapterIndex;
        this.title = title;
        this.content = content;
    }

    /**
     * 读取章节主键。
     *
     * @return 章节主键。
     */
    public Long getId() {
        return id;
    }

    /**
     * 读取章节所属项目。
     *
     * @return 所属项目实体。
     */
    public NovelProject getProject() {
        return project;
    }

    /**
     * 读取章节顺序。
     *
     * @return 从 1 开始的章节序号。
     */
    public int getChapterIndex() {
        return chapterIndex;
    }

    /**
     * 读取章节标题。
     *
     * @return 章节标题。
     */
    public String getTitle() {
        return title;
    }

    /**
     * 读取章节正文。
     *
     * @return 章节正文。
     */
    public String getContent() {
        return content;
    }
}
