package com.devmate.knowledge;

import com.devmate.ai.embedding.*;
import com.devmate.knowledge.index.*;
import com.devmate.knowledge.infrastructure.QdrantVectorStore;
import com.devmate.knowledge.retrieval.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class QdrantVectorStoreIntegrationTest {
    @Container static final GenericContainer<?> QDRANT=new GenericContainer<>(DockerImageName.parse("qdrant/qdrant@sha256:12364fe851b9f17356fc88189fc06d1b521262e04659ec7345975b00c9246a10"))
            .withExposedPorts(6333).withCreateContainerCmdModifier(command->command.getHostConfig().withPortBindings(new com.github.dockerjava.api.model.PortBinding(
                    com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1",0),new com.github.dockerjava.api.model.ExposedPort(6333))));
    final ObjectMapper json=new ObjectMapper();HttpServer relay;HttpClient upstream;String origin,path;QdrantVectorStore store;volatile boolean drop;
    final java.util.concurrent.atomic.AtomicInteger requests=new java.util.concurrent.atomic.AtomicInteger();
    @BeforeEach void start()throws Exception{
        upstream=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();relay=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        relay.createContext("/",exchange->{try{
            requests.incrementAndGet();
            var request=HttpRequest.newBuilder(URI.create("http://"+QDRANT.getHost()+":"+QDRANT.getMappedPort(6333)+exchange.getRequestURI())).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json")
                    .method(exchange.getRequestMethod(),HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes())).build();
            var response=upstream.send(request,HttpResponse.BodyHandlers.ofByteArray());
            if(drop && exchange.getRequestMethod().equals("PUT") && exchange.getRequestURI().getPath().endsWith("/points")){exchange.close();return;}
            exchange.sendResponseHeaders(response.statusCode(),response.body().length);exchange.getResponseBody().write(response.body());
        }catch(Exception ignored){}finally{exchange.close();}});relay.start();origin="http://127.0.0.1:"+relay.getAddress().getPort();String collection="devmate_qwen3_v1_"+UUID.randomUUID().toString().replace("-","");path="/collections/"+collection;store=new QdrantVectorStore(origin,collection);
    }
    @AfterEach void stop(){store.close();relay.stop(0);upstream.close();}
    private IndexWork work(long owner,long project,long document,long processing,long index){return new IndexWork(index,owner,project,document,processing,1,"a".repeat(64),"b".repeat(64),EmbeddingSpec.ID,"RUNNING",false,1,3,4608,2,false,1,"lease",null,null);}
    private IndexPoint point(){return new IndexPoint(0,UUID.randomUUID().toString(),"c".repeat(64),3,false);}
    private List<float[]> vector(){float[] value=new float[1024];value[0]=1;return Collections.singletonList(value);}
    private void call(String method,String suffix,Object data)throws Exception{upstream.send(HttpRequest.newBuilder(URI.create(origin+path+suffix)).header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(data))).build(),HttpResponse.BodyHandlers.ofByteArray());}
    @Test void completeSourceAndBigintIsolationAndRepeatedUpsertAndCleanup(){var work=work(9007199254740993L,42,101,31,41);var point=point();store.upsert(work,List.of(point),vector());store.upsert(work,List.of(point),vector());assertThat(store.matches(work,List.of(point))).isTrue();assertThat(store.matches(work(9007199254740992L,42,101,31,41),List.of(point))).isFalse();assertThat(store.matches(work(work.owner(),43,101,31,41),List.of(point))).isFalse();assertThat(store.matches(work(work.owner(),42,101,32,41),List.of(point))).isFalse();assertThat(store.deleteAndVerify(work,List.of(point))).isTrue();assertThat(store.deleteAndVerify(work,List.of(point))).isTrue();}
    @Test void sameNumberWithWrongOrdinalAndDigestFailsManifestValidation()throws Exception{var work=work(1,2,3,4,5);var point=point();store.upsert(work,List.of(point),vector());var payload=new HashMap<>(QdrantVectorStore.payload(work,point));payload.put("ordinal",1);call("POST","/points/payload?wait=true",Map.of("payload",payload,"points",List.of(point.id())));assertThat(store.matches(work,List.of(point))).isFalse();}
    @Test void lostAcknowledgementIsUnknownEvenWhenPointExists(){var work=work(1,2,3,4,5);var point=point();drop=true;assertThatThrownBy(()->store.upsert(work,List.of(point),vector())).isInstanceOf(EmbeddingFailure.class);drop=false;assertThat(store.matches(work,List.of(point))).isTrue();}
    @Test void incompatibleExistingCollectionFailsReadiness()throws Exception{String collection="devmate_qwen3_v1_incompatible";String target="/collections/"+collection;upstream.send(HttpRequest.newBuilder(URI.create(origin+target)).header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString("{\"vectors\":{\"size\":4,\"distance\":\"Dot\"}}")).build(),HttpResponse.BodyHandlers.ofByteArray());assertThatThrownBy(()->new QdrantVectorStore(origin,collection)).isInstanceOf(EmbeddingFailure.class);}
    private RetrievalSource source(IndexWork work){return new RetrievalSource(work.owner(),work.project(),work.document(),work.processing(),work.id(),work.spec(),work.sourceSha(),"synthetic.md",1,1,"parser","strategy");}
    @Test void retrievalFiltersHundredsOfRetiredPointsAndCrossTuplesBeforeRankingAndExcludesCheckedIds(){
        long owner=9007199254740993L;var first=work(owner,42,101,31,41);var second=work(owner,42,102,32,42);var retired=work(owner,42,101,30,40);
        for(int batch=0;batch<51;batch++){var points=new ArrayList<IndexPoint>();var vectors=new ArrayList<float[]>();for(int n=0;n<4;n++){points.add(new IndexPoint(batch*4+n,UUID.randomUUID().toString(),"c".repeat(64),3,false));vectors.add(vector().getFirst());}store.upsert(retired,points,vectors);}
        float[] lower=new float[1024];lower[0]=0.8f;lower[1]=0.6f;var a=point();var b=point();store.upsert(first,List.of(a),Collections.singletonList(lower));store.upsert(second,List.of(b),Collections.singletonList(lower));
        for(var decoy:List.of(work(owner,42,101,32,42),work(owner,42,102,31,41),work(owner-1,42,101,31,41),work(owner,43,101,31,41)))store.upsert(decoy,List.of(point()),vector());
        var sources=List.of(source(first),source(second));var found=store.query(owner,42,EmbeddingSpec.ID,sources,vector().getFirst(),Set.of(),100);
        assertThat(found.stream().map(VectorCandidate::pointId).toList()).containsExactlyInAnyOrder(a.id(),b.id());assertThat(found).allSatisfy(hit->assertThat(hit.score()).isCloseTo(0.8,within(0.001)));
        var next=store.query(owner,42,EmbeddingSpec.ID,sources,vector().getFirst(),Set.of(a.id()),100);assertThat(next).singleElement().satisfies(hit->assertThat(hit.pointId()).isEqualTo(b.id()));
        assertThat(store.query(owner,42,EmbeddingSpec.ID,sources,vector().getFirst(),Set.of(a.id(),b.id()),100)).isEmpty();
    }
    @Test void retrievalRejectsPayloadThatSpoofsAQualifiedSourceKey()throws Exception{
        var work=work(1,2,3,4,5);var point=point();store.upsert(work,List.of(point),vector());call("POST","/points/payload?wait=true",Map.of("payload",Map.of("document_id","99"),"points",List.of(point.id())));
        assertThatThrownBy(()->store.query(1,2,EmbeddingSpec.ID,List.of(source(work)),vector().getFirst(),Set.of(),100)).isInstanceOf(EmbeddingFailure.class);
    }
    @Test void retrievalCannotAcceptCrossOwnerSourceListsOrDifferentSpec(){
        var work=work(1,2,3,4,5);
        assertThatThrownBy(()->store.query(2,2,EmbeddingSpec.ID,List.of(source(work)),vector().getFirst(),Set.of(),100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->store.query(1,2,"different",List.of(source(work)),vector().getFirst(),Set.of(),100)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void disablingNewIndexingKeepsActualCleanupAndDoesNotCreateMissingCollection()throws Exception{
        var work=work(1,2,3,4,5);var point=point();store.upsert(work,List.of(point),vector());
        var context=new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(com.devmate.knowledge.config.IndexConfiguration.class)
            .withPropertyValues("devmate.knowledge.indexing.vector-origin="+origin);
        requests.set(0);context.withPropertyValues("devmate.knowledge.indexing.collection="+path.substring("/collections/".length())).run(c->{assertThat(requests).hasValue(0);assertThat(c.getBean(VectorStore.class).deleteAndVerify(work,List.of(point))).isTrue();assertThat(store.matches(work,List.of(point))).isFalse();});
        String missing="devmate_qwen3_v1_missing";
        context.withPropertyValues("devmate.knowledge.indexing.collection="+missing).run(c->{assertThatThrownBy(()->c.getBean(VectorStore.class).deleteAndVerify(work,List.of(point))).isInstanceOf(EmbeddingFailure.class);var response=upstream.send(HttpRequest.newBuilder(URI.create(origin+"/collections/"+missing)).GET().build(),HttpResponse.BodyHandlers.discarding());assertThat(response.statusCode()).isEqualTo(404);});
    }
}
