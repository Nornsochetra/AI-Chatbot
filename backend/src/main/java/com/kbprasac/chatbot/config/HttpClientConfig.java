package com.kbprasac.chatbot.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient botpressRestClient(BotpressProperties props) {
        return RestClient.builder().baseUrl(props.baseUrl()).build();
    }

    /** Gemini voice calls; the API key is added per request so it never sits in shared client state. */
    @Bean
    public RestClient geminiRestClient(GeminiProperties props) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(props.timeout());
        return RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
    }
}
