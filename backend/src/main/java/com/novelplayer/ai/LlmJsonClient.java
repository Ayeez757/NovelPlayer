package com.novelplayer.ai;

public interface LlmJsonClient {
// 阶段名称，系统提示词，用户提示词
String requestJson(String stageName, String systemPrompt, String userPrompt);


}
