package com.novelplayer.infra.repository;

import com.novelplayer.domain.generation.GenerationJob;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 生成任务仓储。
 */
public interface GenerationJobRepository extends JpaRepository<GenerationJob, Long> {
}
