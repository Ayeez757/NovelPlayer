package com.novelplayer.domain.generation;

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
 * 预留的阶段结果表，用于后续多阶段或异步生成流程。
 *
 * 当前同步版本暂未写入它；保留实体是为了让数据库结构先支持阶段快照。
 */
@Entity
@Table(name = "generation_stage_result")
public class GenerationStageResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private GenerationJob job;

    @Column(name = "stage_name", nullable = false)
    private String stageName;

    // 阶段状态复用任务状态枚举，表达单个步骤的成功或失败。
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GenerationStatus status;

    // 未来可用于判断相同输入是否已经生成过，支持缓存或跳过重复阶段。
    @Column(name = "input_hash")
    private String inputHash;

    // 阶段输出保留 JSON，便于排查模型中间结果以及恢复流水线。
    @Column(name = "output_json", columnDefinition = "LONGTEXT")
    private String outputJson;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /**
     * JPA 反射创建实体时使用。
     */
    protected GenerationStageResult() {
    }

    /**
     * 创建单个生成阶段的结果快照。
     *
     * @param job 所属生成任务。
     * @param stageName 阶段名称。
     * @param status 阶段状态。
     * @param inputHash 阶段输入摘要。
     * @param outputJson 阶段输出 JSON。
     * @param errorMessage 阶段失败原因。
     */
    public GenerationStageResult(GenerationJob job, String stageName, GenerationStatus status, String inputHash,
                                 String outputJson, String errorMessage) {
        this.job = job;
        this.stageName = stageName;
        this.status = status;
        this.inputHash = inputHash;
        this.outputJson = outputJson;
        this.errorMessage = errorMessage;
    }

    /**
     * 首次持久化前记录阶段结果创建时间。
     */
    @PrePersist
    void prePersist() {
        // 阶段结果是生成任务中产生的快照。
        createdAt = OffsetDateTime.now();
    }
}
