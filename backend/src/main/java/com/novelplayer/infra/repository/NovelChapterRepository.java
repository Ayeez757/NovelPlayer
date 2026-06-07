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
     *
     * @param projectId 项目主键。
     * @return 按章节顺序排列的章节列表。
     */
    List<NovelChapter> findByProjectIdOrderByChapterIndex(Long projectId);

    // 给 chapter_digest 进度统计使用，避免重新拉整章列表只为数总数。
    long countByProjectId(Long projectId);
}
