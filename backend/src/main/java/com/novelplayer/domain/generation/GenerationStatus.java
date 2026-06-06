package com.novelplayer.domain.generation;

/**
 * 生成任务和后续阶段结果共用的状态枚举。
 */
public enum GenerationStatus {
    /**
     * 已创建但尚未开始运行。
     */
    PENDING,

    /**
     * 正在运行。
     */
    RUNNING,

    /**
     * 已成功完成。
     */
    SUCCEEDED,

    /**
     * 运行失败。
     */
    FAILED
}
