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

@Entity
@Table(name = "script_document")
/**
 * 已生成剧本的持久化快照。
 *
 * JSON 字段保存权威结构，YAML 字段用于预览和下载。
 */
public class ScriptDocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private NovelProject project;

    @Column(name = "schema_version", nullable = false)
    private String schemaVersion;

    @Column(name = "document_json", nullable = false, columnDefinition = "LONGTEXT")
    private String documentJson;

    @Column(name = "yaml_content", nullable = false, columnDefinition = "LONGTEXT")
    private String yamlContent;

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_status", nullable = false)
    private ValidationStatus validationStatus;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected ScriptDocumentEntity() {
    }

    public ScriptDocumentEntity(NovelProject project, String schemaVersion, String documentJson, String yamlContent,
                                ValidationStatus validationStatus) {
        this.project = project;
        this.schemaVersion = schemaVersion;
        this.documentJson = documentJson;
        this.yamlContent = yamlContent;
        this.validationStatus = validationStatus;
    }

    @PrePersist
    void prePersist() {
        // 生成文档是不可变快照，记录创建时间即可。
        createdAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public String getDocumentJson() {
        return documentJson;
    }

    public String getYamlContent() {
        return yamlContent;
    }

    public ValidationStatus getValidationStatus() {
        return validationStatus;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
