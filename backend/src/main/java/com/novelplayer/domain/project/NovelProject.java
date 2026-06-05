package com.novelplayer.domain.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "novel_project")
/**
 * 单个小说改编项目的聚合根。
 */
public class NovelProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(name = "source_text", nullable = false, columnDefinition = "LONGTEXT")
    private String sourceText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProjectStatus status = ProjectStatus.DRAFT;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected NovelProject() {
    }

    public NovelProject(String title, String sourceText) {
        this.title = title;
        this.sourceText = sourceText;
        this.status = ProjectStatus.READY;
    }

    @PrePersist
    void prePersist() {
        // 审计时间放在实体内维护，避免各个服务重复处理。
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getSourceText() {
        return sourceText;
    }

    public ProjectStatus getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void markGenerating() {
        status = ProjectStatus.GENERATING;
    }

    public void markCompleted() {
        status = ProjectStatus.COMPLETED;
    }

    public void markFailed() {
        status = ProjectStatus.FAILED;
    }
}
