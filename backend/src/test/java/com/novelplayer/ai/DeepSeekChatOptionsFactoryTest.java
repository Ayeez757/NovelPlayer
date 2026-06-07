package com.novelplayer.ai;

import com.novelplayer.config.NovelPlayerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers DeepSeek-only request options so provider-specific fields stay isolated from other model clients.
 */
@ExtendWith(MockitoExtension.class)
class DeepSeekChatOptionsFactoryTest {

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Test
    void injectsDisabledThinkingExtraBodyWhenConfigured() {
        NovelPlayerProperties properties = new NovelPlayerProperties();
        when(requestSpec.options(any(OpenAiChatOptions.class))).thenReturn(requestSpec);

        ChatClient.ChatClientRequestSpec result = new DeepSeekChatOptionsFactory(properties).apply(requestSpec);

        assertThat(result).isSameAs(requestSpec);
        ArgumentCaptor<OpenAiChatOptions> optionsCaptor = ArgumentCaptor.forClass(OpenAiChatOptions.class);
        verify(requestSpec).options(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getExtraBody())
                .containsEntry("thinking", Map.of("type", "disabled"));
    }

    @Test
    void leavesRequestUnchangedWhenThinkingModeIsDefault() {
        NovelPlayerProperties properties = new NovelPlayerProperties();
        properties.getAi().getDeepseek().setThinkingMode(NovelPlayerProperties.DeepSeek.ThinkingMode.DEFAULT);

        ChatClient.ChatClientRequestSpec result = new DeepSeekChatOptionsFactory(properties).apply(requestSpec);

        assertThat(result).isSameAs(requestSpec);
        verify(requestSpec, never()).options(any());
    }
}
