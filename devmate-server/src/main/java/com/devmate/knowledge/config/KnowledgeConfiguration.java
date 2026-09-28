package com.devmate.knowledge.config;

import com.devmate.knowledge.application.ObjectStorage;
import com.devmate.knowledge.infrastructure.*;
import jakarta.servlet.MultipartConfigElement;
import java.io.IOException;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(KnowledgeProperties.class)
public class KnowledgeConfiguration {
    @Bean("knowledgeClock") Clock knowledgeClock() { return Clock.systemUTC(); }
    @Bean(destroyMethod = "close")
    ObjectStorage objectStorage(KnowledgeProperties properties, Environment environment) {
        if (!properties.isEnabled()) return new DisabledObjectStorage();
        if (environment.matchesProfiles("prod") && !properties.getEndpoint().startsWith("https://"))
            throw new IllegalStateException("Production document storage requires HTTPS");
        return new S3ObjectStorage(properties);
    }
    @Bean(destroyMethod = "close")
    UploadTempFiles uploadTempFiles(KnowledgeProperties properties) throws IOException {
        return new UploadTempFiles(properties, Clock.systemUTC());
    }
    @Bean
    MultipartConfigElement multipartConfigElement(KnowledgeProperties properties, UploadTempFiles files) {
        return new MultipartConfigElement(files.multipartDirectory().toString(), properties.getMaxFileBytes(), properties.getMaxRequestBytes(), 0);
    }
}
