package com.novelplayer.web;

import com.novelplayer.application.script.ScriptValidationException;
import com.novelplayer.web.dto.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
/**
 * 统一接口异常出口，保证前端始终拿到结构化错误响应。
 */
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        // 参数校验错误通常来自请求体字段缺失或取值越界。
        List<String> messages = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of(400, "Validation failed", messages));
    }

    @ExceptionHandler(ScriptValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleScriptValidation(ScriptValidationException exception) {
        // 剧本结构错误要完整返回，方便前端或作者定位问题。
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of(400, "Script schema validation failed", exception.getErrors()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of(400, "Bad request", List.of(exception.getMessage())));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiErrorResponse> handleRuntime(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of(500, "Internal server error", List.of(exception.getMessage())));
    }
}
