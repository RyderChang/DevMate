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
        if(properties.isEnabled()) return new LocalEmbeddingGateway(properties.getModelOrigin());
        return new EmbeddingGateway() {
            public java.util.List<Integer> count(java.util.List<String> input){throw new EmbeddingFailure("INDEX_DISABLED",true);}
            public java.util.List<float[]> embed(String id,java.util.List<String> input,java.util.List<Integer> counts){throw new EmbeddingFailure("INDEX_DISABLED",true);}
            public boolean ended(String operation){return false;}
        };
    }
    @Bean VectorStore vectorStore(IndexProperties properties) {
        if(properties.isEnabled()) return new QdrantVectorStore(properties.getVectorOrigin(),properties.getCollection());
        return new VectorStore() {
            public void upsert(IndexWork work,java.util.List<IndexPoint> points,java.util.List<float[]> vectors){throw new EmbeddingFailure("INDEX_DISABLED",true);}
            public boolean matches(IndexWork work,java.util.List<IndexPoint> points){return false;}
            public boolean deleteAndVerify(IndexWork work,java.util.List<IndexPoint> points){return false;}
        };
    }
}
