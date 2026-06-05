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

@Entity
@Table(name = "novel_chapter")
/**
 * 从作者粘贴原文中提取出的规范化章节。
 */
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

    protected NovelChapter() {
    }

    public NovelChapter(NovelProject project, int chapterIndex, String title, String content) {
        this.project = project;
        this.chapterIndex = chapterIndex;
        this.title = title;
        this.content = content;
    }

    public Long getId() {
        return id;
    }

    public NovelProject getProject() {
        return project;
    }

    public int getChapterIndex() {
        return chapterIndex;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }
}
