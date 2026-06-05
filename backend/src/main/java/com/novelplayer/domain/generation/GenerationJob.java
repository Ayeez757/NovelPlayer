package com.novelplayer.domain.generation;

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
@Table(name = "generation_job")
/**
 * 记录一个项目的一次生成尝试。
 */
public class GenerationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private NovelProject project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GenerationStatus status = GenerationStatus.PENDING;

    @Column(name = "current_stage", nullable = false)
    private String currentStage = "created";

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    protected GenerationJob() {
    }

    public GenerationJob(NovelProject project) {
        this.project = project;
    }

    @PrePersist
    void prePersist() {
        // 生成任务是追加式尝试，创建时只需要记录开始时间。
        createdAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public NovelProject getProject() {
        return project;
    }

    public GenerationStatus getStatus() {
        return status;
    }

    public String getCurrentStage() {
        return currentStage;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    public void markRunning(String stage) {
        status = GenerationStatus.RUNNING;
        currentStage = stage;
    }

    public void moveToStage(String stage) {
        currentStage = stage;
    }

    public void markSucceeded() {
        status = GenerationStatus.SUCCEEDED;
        currentStage = "completed";
        finishedAt = OffsetDateTime.now();
    }

    public void markFailed(String message) {
        status = GenerationStatus.FAILED;
        errorMessage = message;
        finishedAt = OffsetDateTime.now();
    }
}
