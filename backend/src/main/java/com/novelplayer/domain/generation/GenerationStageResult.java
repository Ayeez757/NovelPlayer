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

@Entity
@Table(name = "generation_stage_result")
/**
 * 预留的阶段结果表，用于后续多阶段或异步生成流程。
 */
public class GenerationStageResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private GenerationJob job;

    @Column(name = "stage_name", nullable = false)
    private String stageName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GenerationStatus status;

    @Column(name = "input_hash")
    private String inputHash;

    @Column(name = "output_json", columnDefinition = "LONGTEXT")
    private String outputJson;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected GenerationStageResult() {
    }

    public GenerationStageResult(GenerationJob job, String stageName, GenerationStatus status, String inputHash,
                                 String outputJson, String errorMessage) {
        this.job = job;
        this.stageName = stageName;
        this.status = status;
        this.inputHash = inputHash;
        this.outputJson = outputJson;
        this.errorMessage = errorMessage;
    }

    @PrePersist
    void prePersist() {
        // 阶段结果是生成任务中产生的快照。
        createdAt = OffsetDateTime.now();
    }
}
