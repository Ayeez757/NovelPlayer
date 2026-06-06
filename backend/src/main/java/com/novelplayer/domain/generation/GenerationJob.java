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

/**
 * 记录一个项目的一次生成尝试。
 *
 * 同一个项目可以有多次 Job，用来保留重试、失败和未来异步进度历史。
 */
@Entity
@Table(name = "generation_job")
public class GenerationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private NovelProject project;

    // 状态是任务级别的粗粒度结果，阶段细节通过 currentStage 或阶段结果表表达。
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GenerationStatus status = GenerationStatus.PENDING;

    // 使用字符串是为了将来可以新增阶段名称，而不需要立即修改数据库枚举。
    @Column(name = "current_stage", nullable = false)
    private String currentStage = "created";

    // 失败原因保存原始消息，前端可展示，也方便本地排查模型或校验错误。
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    /**
     * JPA 反射创建实体时使用。
     */
    protected GenerationJob() {
    }

    /**
     * 创建一个项目的生成任务。
     *
     * @param project 所属项目。
     */
    public GenerationJob(NovelProject project) {
        this.project = project;
    }

    /**
     * 首次持久化前记录任务创建时间。
     */
    @PrePersist
    void prePersist() {
        // 生成任务是追加式尝试，创建时只需要记录开始时间。
        createdAt = OffsetDateTime.now();
    }

    /**
     * 读取任务主键。
     *
     * @return 任务主键。
     */
    public Long getId() {
        return id;
    }

    /**
     * 读取任务所属项目。
     *
     * @return 所属项目实体。
     */
    public NovelProject getProject() {
        return project;
    }

    /**
     * 读取任务状态。
     *
     * @return 生成任务状态。
     */
    public GenerationStatus getStatus() {
        return status;
    }

    /**
     * 读取当前阶段名称。
     *
     * @return 当前阶段。
     */
    public String getCurrentStage() {
        return currentStage;
    }

    /**
     * 读取失败错误信息。
     *
     * @return 错误信息，任务未失败时可能为空。
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * 读取任务创建时间。
     *
     * @return 创建时间。
     */
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * 读取任务完成或失败时间。
     *
     * @return 结束时间，运行中时为空。
     */
    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    /**
     * 将任务标记为运行中并记录当前阶段。
     *
     * @param stage 阶段名称。
     */
    public void markRunning(String stage) {
        // 进入运行态时同步记录第一个阶段，便于前端展示“正在做什么”。
        status = GenerationStatus.RUNNING;
        currentStage = stage;
    }

    /**
     * 移动任务到新的阶段。
     *
     * @param stage 阶段名称。
     */
    public void moveToStage(String stage) {
        currentStage = stage;
    }

    /**
     * 将任务标记为成功完成。
     */
    public void markSucceeded() {
        status = GenerationStatus.SUCCEEDED;
        currentStage = "completed";
        finishedAt = OffsetDateTime.now();
    }

    /**
     * 将任务标记为失败并保存错误信息。
     *
     * @param message 失败原因。
     */
    public void markFailed(String message) {
        status = GenerationStatus.FAILED;
        errorMessage = message;
        finishedAt = OffsetDateTime.now();
    }
}
