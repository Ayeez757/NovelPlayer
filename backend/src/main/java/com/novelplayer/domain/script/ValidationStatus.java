package com.novelplayer.domain.script;

/**
 * 已存储剧本文档的校验状态。
 */
public enum ValidationStatus {
    /**
     * 剧本文档已通过结构和引用校验。
     */
    VALID,

    /**
     * 剧本文档存在结构或引用问题。
     */
    INVALID
}
