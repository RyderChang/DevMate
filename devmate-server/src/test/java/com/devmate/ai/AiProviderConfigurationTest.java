package com.devmate.ai;

import com.devmate.ai.application.AiGateway;
import com.devmate.ai.config.AiConfiguration;
import com.devmate.ai.config.AiProperties;
import com.devmate.ai.infrastructure.deepseek.DeepSeekChatGateway;
import com.devmate.ai.infrastructure.openai.OpenAiResponsesGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.*;

class AiProviderConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(AiConfiguration.class).withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void enablesDeepSeekWithoutOpenAiCredentials() {
        context.withPropertyValues("devmate.ai.enabled=true", "devmate.ai.provider=deepseek",
                "devmate.ai.deepseek.api-key=" + UUID.randomUUID(), "devmate.ai.deepseek.model=deepseek-flash")
                .run(application -> {
                    assertThat(application).hasNotFailed().hasSingleBean(AiGateway.class);
                    assertThat(application.getBean(AiGateway.class)).isInstanceOf(DeepSeekChatGateway.class);
                    assertThat(application.getBean(AiGateway.class).provider()).isEqualTo("deepseek");
                });
    }

    @Test
    void preservesOpenAiConfigurationAndDisabledStartup() {
        context.withPropertyValues("devmate.ai.enabled=true", "devmate.ai.openai.api-key=" + UUID.randomUUID(),
                "devmate.ai.openai.model=test-model").run(application -> {
                    assertThat(application).hasNotFailed().hasSingleBean(AiGateway.class);
                    assertThat(application.getBean(AiGateway.class)).isInstanceOf(OpenAiResponsesGateway.class);
                });
        context.withPropertyValues("devmate.ai.provider=deepseek", "devmate.ai.deepseek.model=deepseek-flash")
                .run(application -> {
                    assertThat(application).hasNotFailed().hasSingleBean(AiGateway.class);
                    assertThat(application.getBean(AiGateway.class).enabled()).isFalse();
                    assertThat(application.getBean(AiGateway.class).model()).isEqualTo("deepseek-flash");
                });
    }

    @Test
    void rejectsMissingDeepSeekCredentialsAndUnknownProviders() {
        context.withPropertyValues("devmate.ai.enabled=true", "devmate.ai.provider=deepseek")
                .run(application -> assertThat(application).hasFailed());
        context.withPropertyValues("devmate.ai.provider=unknown")
                .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void confinesDeepSeekCredentialsToOfficialHttpsOriginWithoutEchoingBadConfiguration() {
        for (String endpoint : List.of("http://api.deepseek.com", "https://other.example", "https://api.deepseek.com.evil.test",
                "https://api.deepseek.com:8443", "https://api.deepseek.com/beta", "https://private-user:private-value@api.deepseek.com",
                "https://api.deepseek.com?private-query=value", "https://api.deepseek.com/#private-fragment", "bad private URI")) {
            var properties = new AiProperties();
            properties.setProvider("deepseek");
            properties.getDeepseek().setBaseUrl(endpoint);
            assertThatThrownBy(properties::afterPropertiesSet).isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("private").hasMessageNotContaining(endpoint).hasCause(null);
        }
        for (String endpoint : List.of("https://api.deepseek.com", "https://api.deepseek.com/", "https://api.deepseek.com/v1",
                "https://api.deepseek.com/v1/")) {
            var properties = new AiProperties();
            properties.setProvider("deepseek");
            properties.getDeepseek().setBaseUrl(endpoint);
            properties.afterPropertiesSet();
        }
    }

    @Test
    void rejectsWhitespaceAndControlCharactersInEnabledModelAndKey() {
        for (String value : List.of(" bad ", "bad\r\nheader")) {
            var properties = new AiProperties();
            properties.setProvider("deepseek");
            properties.setEnabled(true);
            properties.getDeepseek().setApiKey(value);
            properties.getDeepseek().setModel("deepseek-flash");
            assertThatThrownBy(properties::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
            properties.getDeepseek().setApiKey(UUID.randomUUID().toString());
            properties.getDeepseek().setModel(value);
            assertThatThrownBy(properties::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
        }
    }
}
