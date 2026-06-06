package com.novelplayer.infra.repository;

import com.novelplayer.domain.script.ScriptDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 剧本文档仓储。
 */
public interface ScriptDocumentRepository extends JpaRepository<ScriptDocumentEntity, Long> {

    /**
     * 前端预览和下载默认读取项目最新一次生成结果。
     *
     * @param projectId 项目主键。
     * @return 最近创建的剧本文档快照。
     */
    Optional<ScriptDocumentEntity> findFirstByProjectIdOrderByCreatedAtDesc(Long projectId);
}
