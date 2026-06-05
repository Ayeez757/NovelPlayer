package com.novelplayer.web.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 统一错误响应格式，避免前端为不同异常写多套解析逻辑。
 */
public record ApiErrorResponse(
        OffsetDateTime timestamp,
        int status,
        String error,
        List<String> messages
) {

    /**
     * 便捷工厂方法，统一设置错误发生时间。
     */
    public static ApiErrorResponse of(int status, String error, List<String> messages) {
        return new ApiErrorResponse(OffsetDateTime.now(), status, error, messages);
    }
}
