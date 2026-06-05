package com.novelplayer.domain.generation;

/**
 * 生成任务和后续阶段结果共用的状态枚举。
 */
public enum GenerationStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED
}
