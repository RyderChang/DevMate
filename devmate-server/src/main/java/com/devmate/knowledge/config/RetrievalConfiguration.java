package com.devmate.knowledge.config;

import com.devmate.ai.embedding.EmbeddingFailure;
import com.devmate.knowledge.infrastructure.QdrantVectorStore;
import com.devmate.knowledge.retrieval.VectorSearch;
import org.springframework.context.annotation.*;

@Configuration
public class RetrievalConfiguration {
    @Bean VectorSearch vectorSearch(RetrievalProperties retrieval, IndexProperties index){
        if(retrieval.isEnabled()) return new QdrantVectorStore(index.getVectorOrigin(),index.getCollection());
        return (owner,project,spec,sources,vector,excluded,limit)->{throw new EmbeddingFailure("RETRIEVAL_DISABLED",true);};
    }
}
