package com.novelplayer.web;

import com.novelplayer.application.script.ScriptValidationException;
import com.novelplayer.web.dto.ApiErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * 统一接口异常出口，保证前端始终拿到结构化错误响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理请求参数校验失败。
     *
     * @param exception Spring Validation 抛出的字段校验异常。
     * @return 400 错误响应。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        //  ResponseEntity   Spring 专用 HTTP 响应载体（状态码，响应头，响应体），参数校验错误通常来自请求体字段缺失或取值越界。
        List<String> messages = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .toList();
        log.warn("Request validation failed errorCount={}", messages.size());
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of(400, "Validation failed", messages));
    }

    /**
     * 处理剧本文档结构或引用校验失败。
     *
     * @param exception 剧本结构校验异常。
     * @return 400 错误响应。
     */
    @ExceptionHandler(ScriptValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleScriptValidation(ScriptValidationException exception) {
        // 剧本结构错误要完整返回，方便前端或作者定位问题。
        log.warn("Script schema validation exception errorCount={}", exception.getErrors().size());
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of(400, "Script schema validation failed", exception.getErrors()));
    }

    /**
     * 处理业务参数不合法，例如项目不存在或章节数量不足。
     *
     * @param exception 非法参数异常。
     * @return 400 错误响应。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
        log.warn("Bad request rejected message={}", exception.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of(400, "Bad request", List.of(exception.getMessage())));
    }

    /**
     * 兜底处理运行时异常。
     *
     * @param exception 未被前面处理器捕获的运行时异常。
     * @return 500 错误响应。
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiErrorResponse> handleRuntime(RuntimeException exception) {
        log.error("Unhandled runtime exception", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of(500, "Internal server error", List.of(exception.getMessage())));
    }
}
