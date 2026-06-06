package com.novelplayer.domain.project;

/**
 * 项目生命周期状态，供后端流程和前端展示共同使用。
 */
public enum ProjectStatus {
    /**
     * 草稿态，保留给未来编辑项目元信息。
     */
    DRAFT,

    /**
     * 项目已创建且章节已拆分，可以开始生成。
     */
    READY,

    /**
     * 正在生成剧本。
     */
    GENERATING,

    /**
     * 最近一次生成已完成。
     */
    COMPLETED,

    /**
     * 最近一次生成失败。
     */
    FAILED
}
