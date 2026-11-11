package com.kbprasac.chatbot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * The built-in agent console lives at static/agent/index.html. It is only served with
     * app.handover.inbox=console; in Botpress Desk mode {@link ConsolePageFilter} answers 404.
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/agent", "/agent/index.html");
        registry.addRedirectViewController("/agent/", "/agent/index.html");
    }
}
