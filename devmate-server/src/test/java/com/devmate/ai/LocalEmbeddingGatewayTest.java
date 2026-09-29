package com.devmate.ai;

import com.devmate.ai.embedding.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class LocalEmbeddingGatewayTest {
    final ObjectMapper json=new ObjectMapper();
    HttpServer server;String origin;volatile String mode;final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
    ExecutorService threads;final java.util.concurrent.atomic.AtomicInteger requests=new java.util.concurrent.atomic.AtomicInteger();
    @BeforeEach void start() throws Exception {
        mode="valid";server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);threads=Executors.newCachedThreadPool();server.setExecutor(threads);
        server.createContext("/",exchange->{
            try{
                requests.incrementAndGet();
                Map<String,Object> body=new HashMap<>(Map.of("spec",EmbeddingSpec.ID,"fingerprint",EmbeddingSpec.FINGERPRINT));
                if(exchange.getRequestURI().getPath().equals("/tokenize")){
                    var input=json.readTree(exchange.getRequestBody()).path("input");body.put("counts",Collections.nCopies(input.size(),2));
                }else if(exchange.getRequestURI().getPath().equals("/embeddings")){
                    calls.incrementAndGet();var input=json.readTree(exchange.getRequestBody());body.put("model",EmbeddingSpec.MODEL);body.put("operation_id",input.path("operation_id").asText());body.put("object","list");
                    body.put("usage",Map.of("total_tokens",mode.equals("usage")?99:4));
                    double[] a=new double[1024];a[0]=1;double[] b=new double[1024];b[1]=1;
                    if(mode.equals("overflow"))a[0]=1e308;if(mode.equals("underflow")){Arrays.fill(a,1e-310);}if(mode.equals("norm"))a[0]=2;
                    body.put("data",List.of(Map.of("index",mode.equals("duplicate")?0:1,"object","embedding","embedding",b),Map.of("index",0,"object","embedding","embedding",a)));
                    if(mode.equals("boolean"))body.put("data",List.of(Map.of("index",true,"object","embedding","embedding",a),Map.of("index",1,"object","embedding","embedding",b)));
                    if(mode.equals("slow")){exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write('{');exchange.getResponseBody().flush();Thread.sleep(2000);return;}
                }else if(exchange.getRequestURI().getPath().startsWith("/operations/")){body.put("operation_id",exchange.getRequestURI().getPath().substring(12));body.put("state","SUCCEEDED");}
                if(mode.equals("fingerprint"))body.put("fingerprint","wrong");
                int status=mode.equals("redirect")?302:200;
                if(mode.equals("no-handshake") && exchange.getRequestURI().getPath().equals("/spec"))status=503;
                if(mode.startsWith("rejected") && exchange.getRequestURI().getPath().equals("/embeddings")){
                    status=mode.equals("rejected-status")?503:429;body.put("state",mode.equals("rejected-state")?"UNKNOWN":"NOT_STARTED");
                    body.put("code",mode.equals("rejected-full")?"JOURNAL_FULL":mode.equals("rejected-unknown")?"OTHER":"BUSY");
                    if(mode.equals("rejected-id"))body.put("operation_id",UUID.randomUUID().toString());
                    if(mode.equals("rejected-fingerprint"))body.put("fingerprint","wrong");
                    if(mode.equals("rejected-spec"))body.put("spec","wrong");
                }
                if(mode.equals("absent"))body.put("state","ABSENT");
                byte[] raw=json.writeValueAsBytes(body);if(mode.equals("utf8"))raw=new byte[]{(byte)255};if(mode.equals("keys") || mode.equals("rejected-malformed"))raw="{\"spec\":1,\"spec\":2}".getBytes();
                if(mode.equals("oversize"))raw=new byte[1048577];
                exchange.sendResponseHeaders(status,raw.length);exchange.getResponseBody().write(raw);
            }catch(Exception ignored){}finally{exchange.close();}
        });server.start();origin="http://127.0.0.1:"+server.getAddress().getPort();
    }
    @AfterEach void stop(){server.stop(0);threads.shutdownNow();}
    @Test void acceptsReorderedVectorsWithExactUsage(){try(var gateway=new LocalEmbeddingGateway(origin)){var vectors=gateway.embed(UUID.randomUUID().toString(),List.of("a","b"),List.of(2,2));assertThat(vectors.getFirst()[0]).isEqualTo(1);assertThat(vectors.getLast()[1]).isEqualTo(1);}}
    @Test void rejectsInvalidHandshakeAndOrigins(){mode="fingerprint";assertThatThrownBy(()->new LocalEmbeddingGateway(origin)).isInstanceOf(EmbeddingFailure.class);for(String value:List.of("http://localhost:8091","http://127.0.0.1:8091/path","https://127.0.0.1:8091","http://127.0.0.1:8091?url=remote"))assertThatThrownBy(()->new LocalJsonClient(value)).isInstanceOf(IllegalArgumentException.class);}
    @Test void rejectsInvalidIndexNumericUsageUtf8AndDuplicateFields(){try(var gateway=new LocalEmbeddingGateway(origin)){for(String value:List.of("duplicate","boolean","overflow","underflow","norm","usage","utf8","keys","oversize","redirect")){mode=value;assertThatThrownBy(()->gateway.embed(UUID.randomUUID().toString(),List.of("a","b"),List.of(2,2))).as(value).isInstanceOf(EmbeddingFailure.class);}}}
    @Test void totalDeadlineIncludesSlowBodyAndDoesNotRetry(){try(var gateway=new LocalEmbeddingGateway(origin,Duration.ofMillis(100))){mode="slow";long start=System.nanoTime();assertThatThrownBy(()->gateway.embed(UUID.randomUUID().toString(),List.of("a","b"),List.of(2,2))).isInstanceOf(EmbeddingFailure.class);assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(1));assertThat(calls).hasValue(1);}}
    @Test void paddingLimitsAndFloatOverflowAreIndependentOfSum(){EmbeddingSpec.counts(List.of(1500,1500,1500,1500),4);assertThatThrownBy(()->EmbeddingSpec.counts(List.of(3000,1500,1),3)).isInstanceOf(EmbeddingFailure.class);assertThatThrownBy(()->EmbeddingSpec.counts(List.of(6001),1)).isInstanceOf(EmbeddingFailure.class);}
    @Test void onlyBoundVerifiedPreDispatchRejectionEstablishesNoInference(){
        try(var gateway=new LocalEmbeddingGateway(origin)){
            for(String value:List.of("rejected-busy","rejected-full")){mode=value;assertThatThrownBy(()->gateway.embed(UUID.randomUUID().toString(),List.of("a","b"),List.of(2,2))).isInstanceOfSatisfying(EmbeddingFailure.class,e->{assertThat(e.code()).isEqualTo("MODEL_NOT_STARTED");assertThat(e.ended()).isTrue();});}
            for(String value:List.of("rejected-id","rejected-fingerprint","rejected-spec","rejected-state","rejected-unknown","rejected-status","rejected-malformed")){mode=value;assertThatThrownBy(()->gateway.embed(UUID.randomUUID().toString(),List.of("a","b"),List.of(2,2))).as(value).isInstanceOfSatisfying(EmbeddingFailure.class,e->assertThat(e.ended()).isFalse());}
            mode="absent";assertThat(gateway.ended(UUID.randomUUID().toString())).isFalse();
        }
    }
    @Test void disabledIndexingKeepsVerifiedStatusAdapterWithoutStartupNetwork()throws Exception{
        mode="no-handshake";
        new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(com.devmate.knowledge.config.IndexConfiguration.class)
            .withPropertyValues("devmate.knowledge.indexing.model-origin="+origin).run(context->{
                EmbeddingGateway gateway=context.getBean(EmbeddingGateway.class);assertThat(requests).hasValue(0);assertThat(gateway.ended(UUID.randomUUID().toString())).isTrue();assertThat(requests).hasValue(1);
                mode="fingerprint";assertThat(gateway.ended(UUID.randomUUID().toString())).isFalse();assertThat(calls).hasValue(0);
            });
    }
}
