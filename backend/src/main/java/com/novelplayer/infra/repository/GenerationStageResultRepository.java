package com.novelplayer.infra.repository;

import com.novelplayer.domain.generation.GenerationStageResult;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 生成阶段结果仓储，预留给多阶段生成流水线。
 */
public interface GenerationStageResultRepository extends JpaRepository<GenerationStageResult, Long> {
}
