package com.devmate.knowledge;

import com.devmate.ai.embedding.*;
import com.devmate.knowledge.config.*;
import com.devmate.knowledge.index.VectorStore;
import com.devmate.knowledge.retrieval.VectorSearch;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class RetrievalConfigurationTest {
    @Test void disabledRetrievalUsesItsDedicatedAdapterWhileIndexCleanupRemainsAvailable(){
        new ApplicationContextRunner().withUserConfiguration(IndexConfiguration.class,RetrievalConfiguration.class).run(context->{
            assertThat(context).hasNotFailed();
            var search=context.getBean(VectorSearch.class);
            assertThat(search).isNotSameAs(context.getBean(VectorStore.class));
            assertThatThrownBy(()->search.query(1,2,EmbeddingSpec.ID,List.of(),new float[1024],Set.of(),1))
                .isInstanceOfSatisfying(EmbeddingFailure.class,e->assertThat(e.code()).isEqualTo("RETRIEVAL_DISABLED"));
        });
    }
}
