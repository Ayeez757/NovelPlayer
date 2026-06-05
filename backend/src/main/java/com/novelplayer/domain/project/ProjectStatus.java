package com.novelplayer.domain.project;

/**
 * 项目生命周期状态，供后端流程和前端展示共同使用。
 */
public enum ProjectStatus {
    DRAFT,
    READY,
    GENERATING,
    COMPLETED,
    FAILED
}
