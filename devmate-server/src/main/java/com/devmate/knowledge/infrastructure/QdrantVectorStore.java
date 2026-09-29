package com.devmate.knowledge.infrastructure;

import com.devmate.ai.embedding.*;
import com.devmate.knowledge.index.*;
import com.devmate.knowledge.retrieval.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.*;

public final class QdrantVectorStore implements VectorStore,VectorSearch,AutoCloseable {
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
    @Override public List<VectorCandidate> query(long owner,long project,String spec,List<RetrievalSource> sources,float[] vector,Set<String> excluded,int limit){
        ensureReady();
        if(sources.isEmpty() || sources.size()>100 || limit<1 || limit>100 || excluded.size()>200 || !EmbeddingSpec.ID.equals(spec))throw new IllegalArgumentException("Bounded fixed-spec query required");
        EmbeddingSpec.vectors(Collections.singletonList(vector),1);
        var allowed=new HashMap<String,RetrievalSource>();
        for(var source:sources){if(source.owner()!=owner || source.project()!=project || !source.spec().equals(spec) || allowed.put(source.key(),source)!=null)throw new IllegalArgumentException("Owned full source tuples required");}
        var constraints=new ArrayList<Object>();
        constraints.add(Map.of("key","owner_user_id","match",Map.of("value",Long.toString(owner))));
        constraints.add(Map.of("key","project_id","match",Map.of("value",Long.toString(project))));
        constraints.add(Map.of("key","spec","match",Map.of("value",spec)));
        constraints.add(Map.of("key","source_key","match",Map.of("any",sources.stream().map(RetrievalSource::key).toList())));
        var filter=new HashMap<String,Object>();filter.put("must",constraints);
        if(!excluded.isEmpty())filter.put("must_not",List.of(Map.of("has_id",excluded.stream().sorted().toList())));
        var result=call("POST",path+"/points/query",Map.of("query",vector,"filter",filter,"limit",limit,"with_payload",true,"with_vector",false,"params",Map.of("exact",true))).path("result").path("points");
        if(!result.isArray() || result.size()>limit)throw new EmbeddingFailure("VECTOR_INVALID_RESPONSE",true);
        var candidates=new ArrayList<VectorCandidate>();var unique=new HashSet<String>();
        for(var row:result){
            String id=row.path("id").asText();try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw new EmbeddingFailure("VECTOR_INVALID_RESPONSE",true);}
            var payload=row.path("payload");var source=allowed.get(payload.path("source_key").asText());var score=row.path("score");
            if(source==null || excluded.contains(id) || !unique.add(id) || !score.isNumber() || !Double.isFinite(score.doubleValue()) || Math.abs(score.doubleValue())>1.001)
                throw new EmbeddingFailure("VECTOR_INVALID_RESPONSE",true);
            var required=Map.of("owner_user_id",Long.toString(owner),"project_id",Long.toString(project),"spec",spec,"document_id",Long.toString(source.document()),"processing_id",Long.toString(source.processing()),"index_id",Long.toString(source.index()),"source_sha256",source.sourceSha());
            for(var field:required.entrySet())if(!payload.path(field.getKey()).isTextual() || !field.getValue().equals(payload.path(field.getKey()).textValue()))throw new EmbeddingFailure("VECTOR_SOURCE_MISMATCH",true);
            int ordinal=LocalJsonClient.integer(payload.path("ordinal"));String sha=payload.path("chunk_sha256").asText();
            if(ordinal>8191 || !sha.matches("[0-9a-f]{64}"))throw new EmbeddingFailure("VECTOR_INVALID_RESPONSE",true);
            candidates.add(new VectorCandidate(id,score.doubleValue(),source.key(),ordinal,sha,source.sourceSha()));
        }return List.copyOf(candidates);
    }
    @Override public void close() {http.close();}
}
