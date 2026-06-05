package com.novelplayer.infra.repository;

import com.novelplayer.domain.project.NovelProject;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 小说项目仓储。
 */
public interface NovelProjectRepository extends JpaRepository<NovelProject, Long> {
}
