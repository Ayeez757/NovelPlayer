package com.novelplayer.web.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 统一错误响应格式，避免前端为不同异常写多套解析逻辑。
 *
 * @param timestamp 错误响应生成时间。
 * @param status HTTP 状态码。
 * @param error 错误摘要。
 * @param messages 可展示的错误明细。
 */
public record ApiErrorResponse(
        OffsetDateTime timestamp,
        int status,
        String error,
        List<String> messages
) {

    /**
     * 便捷工厂方法，统一设置错误发生时间。
     *
     * @param status HTTP 状态码。
     * @param error 错误摘要。
     * @param messages 错误明细。
     * @return 统一错误响应。
     */
    public static ApiErrorResponse of(int status, String error, List<String> messages) {
        return new ApiErrorResponse(OffsetDateTime.now(), status, error, messages);
    }
}
