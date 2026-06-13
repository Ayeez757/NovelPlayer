package com.novelplayer.ai;


public record ScriptPrompt(String systemPrompt, String userPrompt) {
}
/**
 * 一次模型调用需要的提示词消息。
 *
 * @param systemPrompt 系统内部提示词。
 * @param userPrompt 面向本次作品和生成参数的用户消息。
 */