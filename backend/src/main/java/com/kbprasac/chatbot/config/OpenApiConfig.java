package com.kbprasac.chatbot.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI chatbotOpenApi() {
        return new OpenAPI().info(new Info()
                .title("KB PRASAC AI Chatbot API")
                .description("Backend for the chatbot widget: chat, speech, handover, attachments, admin")
                .version("v1"));
    }
}
