package com.devmate.knowledge;

import com.devmate.knowledge.config.KnowledgeConfiguration;
import com.devmate.knowledge.config.KnowledgeProperties;
import com.devmate.knowledge.application.ObjectStorage;
import com.devmate.knowledge.infrastructure.DisabledObjectStorage;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.*;

class KnowledgePropertiesTest {
    @Test void defaultsAreBoundedAndDisabledWithoutCredentials() {
        var properties = new KnowledgeProperties(); properties.afterPropertiesSet();
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getOperationTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getOperationLease()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.getScanBatchSize()).isEqualTo(50);
        assertThat(properties.getMaxAutomaticRetries()).isEqualTo(5);
        new ApplicationContextRunner().withUserConfiguration(KnowledgeConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ObjectStorage.class)).isInstanceOf(DisabledObjectStorage.class);
        });
    }
    @Test void invalidLimitsOrALeaseWithoutTimeoutMarginFailClosed() {
        for (var change : java.util.List.<java.util.function.Consumer<KnowledgeProperties>>of(
                value -> value.setMaxConcurrentUploads(5), value -> value.setMaxDocuments(101), value -> value.setMaxProjectBytes(-1),
                value -> value.setScanBatchSize(51), value -> value.setMaxAutomaticRetries(6),
                value -> value.setOperationTimeout(Duration.ofSeconds(31)), value -> value.setOperationLease(Duration.ofSeconds(31)),
                value -> value.setScanInterval(Duration.ZERO))) {
            var properties = new KnowledgeProperties(); change.accept(properties);
            assertThatThrownBy(properties::afterPropertiesSet).isInstanceOf(IllegalStateException.class).hasMessage("Invalid devmate.knowledge configuration");
        }
    }
    @Test void enabledEndpointAndCredentialsFailWithoutEchoingValues() {
        var properties = new KnowledgeProperties(); properties.setEnabled(true);
        properties.setAccessKey(UUID.randomUUID().toString()); properties.setSecretKey(UUID.randomUUID().toString());
        for (String endpoint : new String[]{"invalid", "http://127.0.0.1:9000", "https://user:password@example.test", "https://example.test/?secret=x"}) {
            properties.setEndpoint(endpoint);
            assertThatThrownBy(properties::afterPropertiesSet).hasMessage("Invalid devmate.knowledge configuration");
        }
    }
    @Test void explicitLocalHttpDoesNotPermitItInProduction() {
        new ApplicationContextRunner().withUserConfiguration(KnowledgeConfiguration.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("spring.profiles.active=prod", "devmate.knowledge.enabled=true", "devmate.knowledge.allow-local-http=true",
                        "devmate.knowledge.endpoint=http://127.0.0.1:9000", "devmate.knowledge.bucket=synthetic-documents",
                        "devmate.knowledge.access-key=" + UUID.randomUUID(), "devmate.knowledge.secret-key=" + UUID.randomUUID())
                .run(context -> assertThat(context).hasFailed());
    }
}
