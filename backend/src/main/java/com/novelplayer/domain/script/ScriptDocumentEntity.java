package com.novelplayer.domain.script;

import com.novelplayer.domain.project.NovelProject;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * 已生成剧本的持久化快照。
 *
 * JSON 字段保存权威结构，YAML 字段用于预览和下载。
 */
@Entity
@Table(name = "script_document")
public class ScriptDocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private NovelProject project;

    @Column(name = "schema_version", nullable = false)
    private String schemaVersion;

    // 权威结构：后续如果需要重新导出不同格式，应优先读取这一份 JSON。
    @Column(name = "document_json", nullable = false, columnDefinition = "LONGTEXT")
    private String documentJson;

    // 派生展示内容：保存下来可以减少每次打开页面时重复序列化。
    @Column(name = "yaml_content", nullable = false, columnDefinition = "LONGTEXT")
    private String yamlContent;

    // 当前只保存通过校验的结果，字段仍保留状态位，方便未来支持草稿/待修复文档。
    @Enumerated(EnumType.STRING)
    @Column(name = "validation_status", nullable = false)
    private ValidationStatus validationStatus;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /**
     * JPA 反射创建实体时使用。
     */
    protected ScriptDocumentEntity() {
    }

    /**
     * 创建已生成剧本文档的持久化快照。
     *
     * @param project 所属项目。
     * @param schemaVersion 剧本文档结构版本。
     * @param documentJson 权威 JSON 文档。
     * @param yamlContent 派生 YAML 内容。
     * @param validationStatus 校验状态。
     */
    public ScriptDocumentEntity(NovelProject project, String schemaVersion, String documentJson, String yamlContent,
                                ValidationStatus validationStatus) {
        this.project = project;
        this.schemaVersion = schemaVersion;
        this.documentJson = documentJson;
        this.yamlContent = yamlContent;
        this.validationStatus = validationStatus;
    }

    /**
     * 首次持久化前记录文档创建时间。
     */
    @PrePersist
    void prePersist() {
        // 生成文档是不可变快照，记录创建时间即可。
        createdAt = OffsetDateTime.now();
    }

    /**
     * 读取剧本文档主键。
     *
     * @return 剧本文档主键。
     */
    public Long getId() {
        return id;
    }

    /**
     * 读取文档结构版本。
     *
     * @return schema 版本。
     */
    public String getSchemaVersion() {
        return schemaVersion;
    }

    /**
     * 读取权威 JSON 文档。
     *
     * @return JSON 文本。
     */
    public String getDocumentJson() {
        return documentJson;
    }

    /**
     * 读取派生 YAML 文档。
     *
     * @return YAML 文本。
     */
    public String getYamlContent() {
        return yamlContent;
    }

    /**
     * 读取文档校验状态。
     *
     * @return 校验状态。
     */
    public ValidationStatus getValidationStatus() {
        return validationStatus;
    }

    /**
     * 读取文档创建时间。
     *
     * @return 创建时间。
     */
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
