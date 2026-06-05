package com.novelplayer.infra.repository;

import com.novelplayer.domain.project.NovelChapter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 小说章节仓储。
 */
public interface NovelChapterRepository extends JpaRepository<NovelChapter, Long> {

    /**
     * 生成剧本时必须按原始章节顺序读取。
     */
    List<NovelChapter> findByProjectIdOrderByChapterIndex(Long projectId);
}
