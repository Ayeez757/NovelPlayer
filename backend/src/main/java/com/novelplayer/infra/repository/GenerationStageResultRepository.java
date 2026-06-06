package com.novelplayer.infra.repository;

import com.novelplayer.domain.generation.GenerationStatus;
import com.novelplayer.domain.generation.GenerationStageResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 生成阶段结果仓储，预留给多阶段生成流水线。
 */
public interface GenerationStageResultRepository extends JpaRepository<GenerationStageResult, Long> {

    /**
     * 查询同一任务、同一阶段、同一状态和同一输入哈希下最新的一条阶段结果。
     *
     * @param jobId 生成任务主键。
     * @param stageName 阶段名称。
     * @param status 阶段状态。
     * @param inputHash 阶段输入哈希。
     * @return 最新阶段结果；不存在时返回空。
     */
    Optional<GenerationStageResult> findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
            Long jobId, String stageName, GenerationStatus status, String inputHash);
}
