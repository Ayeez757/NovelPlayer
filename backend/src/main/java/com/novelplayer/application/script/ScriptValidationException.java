package com.novelplayer.application.script;

import java.util.List;

/**
 * 携带可展示给用户或前端的结构与引用校验错误。
 */
public class ScriptValidationException extends RuntimeException {

    private final List<String> errors;

    public ScriptValidationException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = errors;
    }

    public List<String> getErrors() {
        return errors;
    }
}
