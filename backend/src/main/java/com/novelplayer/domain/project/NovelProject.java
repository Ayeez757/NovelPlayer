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

/**
 * 单个小说改编项目的聚合根。
 *
 * 项目状态反映从“已准备章节”到“生成中/完成/失败”的整体进度。
 */
@Entity
@Table(name = "novel_project")
public class NovelProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    // 保留完整原文，方便后续重新拆分或基于原文做二次生成。
    @Column(name = "source_text", nullable = false, columnDefinition = "LONGTEXT")
    private String sourceText;

    // 状态使用字符串入库，避免枚举顺序变化导致历史数据含义错位。
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProjectStatus status = ProjectStatus.DRAFT;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * JPA 反射创建实体时使用。
     */
    protected NovelProject() {
    }

    /**
     * 创建新的小说改编项目。
     *
     * @param title 作品标题。
     * @param sourceText 完整小说原文。
     */
    public NovelProject(String title, String sourceText) {
        this.title = title;
        this.sourceText = sourceText;
        this.status = ProjectStatus.READY;
    }

    /**
     * 首次持久化前填充审计时间。
     */
    @PrePersist
    void prePersist() {
        // 审计时间放在实体内维护，避免各个服务重复处理。
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    /**
     * 更新实体前刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    /**
     * 读取项目主键。
     *
     * @return 项目主键。
     */
    public Long getId() {
        return id;
    }

    /**
     * 读取作品标题。
     *
     * @return 作品标题。
     */
    public String getTitle() {
        return title;
    }

    /**
     * 读取完整小说原文。
     *
     * @return 小说原文。
     */
    public String getSourceText() {
        return sourceText;
    }

    /**
     * 读取项目状态。
     *
     * @return 项目生命周期状态。
     */
    public ProjectStatus getStatus() {
        return status;
    }

    /**
     * 读取项目创建时间。
     *
     * @return 创建时间。
     */
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * 读取项目最近更新时间。
     *
     * @return 最近更新时间。
     */
    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 将项目标记为正在生成。
     */
    public void markGenerating() {
        // 当前没有复杂状态机，状态转换集中在实体方法里，避免服务层直接 set 枚举。
        status = ProjectStatus.GENERATING;
    }

    /**
     * 将项目标记为生成完成。
     */
    public void markCompleted() {
        status = ProjectStatus.COMPLETED;
    }

    /**
     * 将项目标记为生成失败。
     */
    public void markFailed() {
        status = ProjectStatus.FAILED;
    }
}
