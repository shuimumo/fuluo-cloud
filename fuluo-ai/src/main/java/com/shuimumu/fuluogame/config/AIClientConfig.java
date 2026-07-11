package com.shuimumu.fuluogame.config;

import com.shuimumu.fuluogame.constants.AiSystemConstants;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AIClientConfig {

    @Bean
    public ChatClient SummariseClient(DeepSeekChatModel deepSeekChatModel) {
        return ChatClient.builder(deepSeekChatModel)
                .defaultSystem(AiSystemConstants.SUMMARISE_SYSTEM_PROMPT)
                .build();
    }

    @Bean
    public ChatClient PolishClient(DeepSeekChatModel deepSeekChatModel) {
        return ChatClient.builder(deepSeekChatModel)
                .defaultSystem(AiSystemConstants.POLISH_SYSTEM_PROMPT)
                .build();
    }

}
