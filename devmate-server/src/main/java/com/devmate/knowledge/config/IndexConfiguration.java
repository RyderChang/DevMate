package com.devmate.knowledge.config;

import com.devmate.ai.embedding.*;
import com.devmate.knowledge.index.*;
import com.devmate.knowledge.infrastructure.QdrantVectorStore;
import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Configuration
@EnableConfigurationProperties(IndexProperties.class)
public class IndexConfiguration {
    @Bean EmbeddingGateway embeddingGateway(IndexProperties properties) {
        return new LocalEmbeddingGateway(properties.getModelOrigin(),properties.isEnabled());
    }
    @Bean VectorStore vectorStore(IndexProperties properties) {
        return new QdrantVectorStore(properties.getVectorOrigin(),properties.getCollection(),properties.isEnabled());
    }
}
