package com.novelplayer.application.script;

import java.util.List;

/**
 * 携带可展示给用户或前端的结构与引用校验错误。
 */
public class ScriptValidationException extends RuntimeException {

    private final List<String> errors;

    /**
     * 创建剧本文档校验异常。
     *
     * @param errors 结构或引用校验错误列表。
     */
    public ScriptValidationException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = errors;
    }

    /**
     * 读取完整校验错误列表。
     *
     * @return 校验错误列表。
     */
    public List<String> getErrors() {
        return errors;
    }
}
