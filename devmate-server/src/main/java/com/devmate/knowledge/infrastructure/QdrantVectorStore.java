package com.devmate.knowledge.infrastructure;

import com.devmate.ai.embedding.*;
import com.devmate.knowledge.index.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.*;

public final class QdrantVectorStore implements VectorStore,AutoCloseable {
    private final LocalJsonClient http;
    private final String path;
    private final boolean initialize;
    private volatile boolean ready;
    public QdrantVectorStore(String origin,String collection) {
        this(origin,collection,true);
    }
    /** Recovery verifies existing state lazily; it never creates a missing collection or payload index. */
    public QdrantVectorStore(String origin,String collection,boolean initialize) {
        if (!collection.matches("devmate_qwen3_v1(?:_[a-z0-9]{1,40})?")) throw new IllegalArgumentException("Collection must identify the frozen spec");
        http=new LocalJsonClient(origin); path="/collections/"+collection;this.initialize=initialize;
        if(initialize)try{ensureReady();}catch(RuntimeException failure){close();throw failure;}
    }
    private synchronized void ensureReady(){
        if(ready)return;
            JsonNode root=call("GET","/",null);
            if(!"1.19.1".equals(root.path("version").asText())) throw new EmbeddingFailure("VECTOR_VERSION_MISMATCH",true);
            JsonNode info;
            try { info=call("GET",path,null); }
            catch(EmbeddingFailure unavailable) { if(!initialize)throw unavailable;call("PUT",path,Map.of("vectors",Map.of("size",1024,"distance","Cosine"))); info=call("GET",path,null); }
            var config=info.path("result").path("config").path("params").path("vectors");
            if(LocalJsonClient.integer(config.path("size"))!=1024 || !"Cosine".equals(config.path("distance").asText())) throw new EmbeddingFailure("VECTOR_SPEC_MISMATCH",true);
            for(String field:List.of("owner_user_id","project_id","spec","source_key")) {
                var type=info.path("result").path("payload_schema").path(field).path("data_type");
                if(!type.isMissingNode() && !"keyword".equals(type.asText())) throw new EmbeddingFailure("VECTOR_SPEC_MISMATCH",true);
                if(type.isMissingNode()){if(!initialize)throw new EmbeddingFailure("VECTOR_SPEC_MISMATCH",true);call("PUT",path+"/index?wait=true",Map.of("field_name",field,"field_schema","keyword"));}
            }
        ready=true;
    }
    private JsonNode call(String method,String path,Object body) { return http.call(method,path,body,Duration.ofSeconds(30)); }
    public static String source(IndexWork work) { return work.document()+"/"+work.processing()+"/"+work.id()+"/"+work.spec(); }
    public static Map<String,Object> payload(IndexWork work,IndexPoint point) {
        return Map.of("owner_user_id",Long.toString(work.owner()),"project_id",Long.toString(work.project()),"document_id",Long.toString(work.document()),"processing_id",Long.toString(work.processing()),"index_id",Long.toString(work.id()),"source_key",source(work),"spec",work.spec(),"ordinal",point.ordinal(),"chunk_sha256",point.sha(),"source_sha256",work.sourceSha());
    }
    private Map<String,Object> filter(IndexWork work,List<IndexPoint> points) {
        if(points.isEmpty() || points.size()>4) throw new IllegalArgumentException("Bounded point batch required");
        return Map.of("must",List.of(Map.of("key","owner_user_id","match",Map.of("value",Long.toString(work.owner()))),Map.of("key","project_id","match",Map.of("value",Long.toString(work.project()))),
                Map.of("key","spec","match",Map.of("value",work.spec())),Map.of("key","source_key","match",Map.of("value",source(work))),Map.of("has_id",points.stream().map(IndexPoint::id).toList())));
    }
    private void completed(JsonNode response) { if(!"completed".equals(response.path("result").path("status").asText())) throw new EmbeddingFailure("VECTOR_UNCONFIRMED",false); }
    @Override public void upsert(IndexWork work,List<IndexPoint> points,List<float[]> vectors) {
        ensureReady();
        if(points.isEmpty() || points.size()>4) throw new IllegalArgumentException("Bounded point batch required"); EmbeddingSpec.vectors(vectors,points.size());
        var rows=new ArrayList<Object>();
        for(int i=0;i<points.size();i++) rows.add(Map.of("id",points.get(i).id(),"vector",vectors.get(i),"payload",payload(work,points.get(i))));
        completed(call("PUT",path+"/points?wait=true&ordering=strong",Map.of("points",rows)));
    }
    private List<JsonNode> inspect(IndexWork work,List<IndexPoint> points) {
        ensureReady();
        JsonNode result=call("POST",path+"/points/scroll",Map.of("filter",filter(work,points),"limit",4,"with_payload",true,"with_vector",false)).path("result");
        if(!result.path("points").isArray() || result.path("points").size()>4 || !result.path("next_page_offset").isNull()) throw new EmbeddingFailure("VECTOR_INVALID_RESPONSE",false);
        var rows=new ArrayList<JsonNode>(); result.path("points").forEach(rows::add); return rows;
    }
    @Override public boolean matches(IndexWork work,List<IndexPoint> points) {
        List<JsonNode> found=inspect(work,points); if(found.size()!=points.size()) return false;
        var expected=new HashMap<String,IndexPoint>(); points.forEach(p->expected.put(p.id(),p));
        for(var row:found) {
            var point=expected.remove(row.path("id").asText()); if(point==null) return false;
            Map<String,Object> payload=payload(work,point);
            for(var field:payload.entrySet()) {
                JsonNode value=row.path("payload").path(field.getKey());
                if(field.getValue() instanceof Integer n) { if(!value.isIntegralNumber() || value.intValue()!=n) return false; }
                else if(!value.isTextual() || !field.getValue().equals(value.textValue())) return false;
            }
        }
        return expected.isEmpty();
    }
    @Override public boolean deleteAndVerify(IndexWork work,List<IndexPoint> points) {
        ensureReady();
        completed(call("POST",path+"/points/delete?wait=true&ordering=strong",Map.of("filter",filter(work,points))));
        return inspect(work,points).isEmpty();
    }
    @Override public void close() {http.close();}
}
