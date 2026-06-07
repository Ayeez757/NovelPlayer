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
//    补 3 个方法。这里是跨 job 复用和进度统计的入口。
    // 按 projectId 查成功阶段结果，这样新建 job 也能复用旧 job 的阶段结果。
    Optional<GenerationStageResult> findFirstByJobProjectIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
            Long projectId, String stageName, GenerationStatus status, String inputHash);

    // 统计当前 job 某类前缀阶段的成功/失败数量，给前端 progress 用。
    long countByJobIdAndStatusAndStageNameStartingWith(
            Long jobId, GenerationStatus status, String stageNamePrefix);

    // 读取当前 job 最新的 scene_plan 成功结果，用来推算 scene_draft 的 total。
    Optional<GenerationStageResult> findFirstByJobIdAndStageNameAndStatusOrderByCreatedAtDesc(
            Long jobId, String stageName, GenerationStatus status);
}
