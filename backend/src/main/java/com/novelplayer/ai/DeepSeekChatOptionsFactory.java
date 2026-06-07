package com.novelplayer.ai;

import com.novelplayer.config.NovelPlayerProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * DeepSeek 请求级选项工厂。
 *
 * <p>DeepSeek 的 {@code thinking} 字段是供应商私有的 OpenAI-compatible 扩展参数，不属于通用
 * OpenAI Chat Completions 标准。这里把它集中封装在 DeepSeek 客户端内部，避免未来接入 OpenAI、
 * Gemini、Claude 或本地 OpenAI-compatible 服务时，把 DeepSeek 私有字段误传给其他模型。</p>
 */
@Component
public class DeepSeekChatOptionsFactory {

    private static final OpenAiChatOptions DISABLED_THINKING_OPTIONS = OpenAiChatOptions.builder()
            .extraBody(Map.of("thinking", Map.of("type", "disabled")))
            .build();

    private final NovelPlayerProperties properties;

    /**
     * 创建 DeepSeek 请求级选项工厂。
     *
     * @param properties 应用配置。
     */
    public DeepSeekChatOptionsFactory(NovelPlayerProperties properties) {
        this.properties = properties;
    }

    /**
     * 根据当前配置为一次 ChatClient 请求追加 DeepSeek 专用选项。
     *
     * <p>当配置为 {@code DEFAULT} 时，该方法原样返回请求，不注入任何厂商扩展字段；当配置为
     * {@code DISABLED} 时，追加 {@code thinking: {type: "disabled"}}，关闭 DeepSeek 思考模式。</p>
     *
     * @param requestSpec 待配置的请求。
     * @return 已应用 DeepSeek 专用选项的请求。
     */
    public ChatClient.ChatClientRequestSpec apply(ChatClient.ChatClientRequestSpec requestSpec) {
        Objects.requireNonNull(requestSpec, "requestSpec must not be null");
        NovelPlayerProperties.DeepSeek.ThinkingMode thinkingMode =
                properties.getAi().getDeepseek().getThinkingMode();
        if (thinkingMode == NovelPlayerProperties.DeepSeek.ThinkingMode.DISABLED) {
            return requestSpec.options(DISABLED_THINKING_OPTIONS);
        }
        return requestSpec;
    }
}
