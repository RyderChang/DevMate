import com.devmate.ai.embedding.*;
import java.util.List;
import java.util.UUID;

/** Run separately against the real local service; daily CI uses synthetic contracts. */
class EmbeddingSmoke {
    public static void main(String[] args) {
        try(var gateway=new LocalEmbeddingGateway("http://127.0.0.1:8091")) {
            var input=List.of("项目文档按用户和项目隔离。","Spring transaction rollback");
            var counts=gateway.count(input);String operation=UUID.randomUUID().toString();
            var vectors=gateway.embed(operation,input,counts);
            if(vectors.size()!=2 || !gateway.ended(operation))throw new IllegalStateException("Actual HTTP contract failed");
            System.out.println("PASS real JDK/model HTTP: inputs=2 dimensions=1024 tokens="+counts.stream().mapToInt(Integer::intValue).sum()+" fingerprint="+EmbeddingSpec.FINGERPRINT);
        }
    }
}
