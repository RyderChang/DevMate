import com.devmate.ai.embedding.*;
import com.devmate.knowledge.application.RetrievalService;
import com.devmate.knowledge.index.*;
import com.devmate.knowledge.infrastructure.QdrantVectorStore;
import com.devmate.knowledge.retrieval.*;
import java.util.*;

/** Synthetic protocol sample only. MySQL authorization is tested separately in the backend suite. */
class RetrievalSmoke {
    static IndexWork work(long document){return new IndexWork(document+100,9007199254740993L,42,document,document+10,1,"a".repeat(64),"b".repeat(64),EmbeddingSpec.ID,"SUCCEEDED",true,1,0,4608,2,false,0,null,null,null);}
    static RetrievalSource source(IndexWork w){return new RetrievalSource(w.owner(),w.project(),w.document(),w.processing(),w.id(),w.spec(),w.sourceSha(),"synthetic.md",1,1,"parser","strategy");}
    public static void main(String[] args){
        String collection="devmate_qwen3_v1_r"+UUID.randomUUID().toString().replace("-","");
        try(var model=new LocalEmbeddingGateway("http://127.0.0.1:8091");var vectors=new QdrantVectorStore("http://127.0.0.1:6333",collection)){
            if(!model.count(List.of(RetrievalService.QUERY_PREFIX+"项目文档按用户和项目隔离。")).equals(List.of(29)))throw new IllegalStateException("Frozen query count fixture mismatch");
            var documents=List.of("Spring @Transactional rolls back on RuntimeException by default. Checked exceptions require rollbackFor.","Banana bread recipe: mix flour, ripe bananas and butter, then bake in an oven.");
            var counts=model.count(documents);String operation=UUID.randomUUID().toString();var encoded=model.embed(operation,documents,counts);if(!model.ended(operation))throw new IllegalStateException("Document operation unconfirmed");
            var works=List.of(work(101),work(102));var points=new ArrayList<IndexPoint>();
            for(int n=0;n<2;n++){var point=new IndexPoint(0,UUID.randomUUID().toString(),EmbeddingSpec.sha(documents.get(n)),counts.get(n),false);points.add(point);vectors.upsert(works.get(n),List.of(point),Collections.singletonList(encoded.get(n)));}
            String query=RetrievalService.QUERY_PREFIX+"When does Spring roll back a transaction?";var queryCounts=model.count(List.of(query));String queryOperation=UUID.randomUUID().toString();var queryVector=model.embed(queryOperation,List.of(query),queryCounts);
            var hits=vectors.query(works.getFirst().owner(),42,EmbeddingSpec.ID,works.stream().map(RetrievalSmoke::source).toList(),queryVector.getFirst(),Set.of(),20);
            if(hits.size()!=2 || !hits.getFirst().pointId().equals(points.getFirst().id()) || !model.ended(queryOperation))throw new IllegalStateException("Synthetic retrieval sample failed");
            var next=vectors.query(works.getFirst().owner(),42,EmbeddingSpec.ID,works.stream().map(RetrievalSmoke::source).toList(),queryVector.getFirst(),Set.of(points.getFirst().id()),20);
            if(next.size()!=1 || !next.getFirst().pointId().equals(points.get(1).id()))throw new IllegalStateException("Candidate exclusion failed");
            for(int n=0;n<2;n++)if(!vectors.deleteAndVerify(works.get(n),List.of(points.get(n))))throw new IllegalStateException("Synthetic cleanup failed");
            System.out.println("PASS actual query model/Qdrant: spec="+EmbeddingSpec.ID+" queryTokens="+queryCounts.getFirst()+" candidates=2 expectedTop1=true exclusion=true score="+hits.getFirst().score());
        }
    }
}
