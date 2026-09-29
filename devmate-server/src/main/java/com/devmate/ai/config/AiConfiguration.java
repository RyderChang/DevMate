package com.devmate.ai.config;

import com.devmate.ai.application.AiGateway;
import com.devmate.ai.infrastructure.deepseek.DeepSeekChatGateway;
import com.devmate.ai.infrastructure.openai.OpenAiResponsesGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "devmate.ai", name = "enabled", havingValue = "true")
    AiGateway enabledAiGateway(AiProperties properties, ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(java.net.HttpURLConnection connection, String method) throws java.io.IOException {
                super.prepareConnection(connection, method);
                connection.setConnectTimeout((int) Math.max(1, com.devmate.ai.application.CallBudget.cap(properties.getConnectTimeout()).toMillis()));
                connection.setReadTimeout((int) Math.max(1, com.devmate.ai.application.CallBudget.cap(properties.getReadTimeout()).toMillis()));
            }
        };
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        RestClient restClient = RestClient.builder()
                .baseUrl("deepseek".equals(properties.getProvider())
                        ? properties.getDeepseek().getBaseUrl() : properties.getOpenai().getBaseUrl())
                .requestFactory(requestFactory)
                .build();
        return "deepseek".equals(properties.getProvider())
                ? new DeepSeekChatGateway(restClient, objectMapper, properties)
                : new OpenAiResponsesGateway(restClient, objectMapper, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "devmate.ai", name = "enabled", havingValue = "false", matchIfMissing = true)
    AiGateway disabledAiGateway(AiProperties properties) {
        return new DisabledAiGateway(properties);
    }
}
