package com.devmate.knowledge.application;

import com.devmate.ai.embedding.*;
import com.devmate.knowledge.dto.RetrievalRequest;
import com.devmate.knowledge.retrieval.*;
import com.devmate.knowledge.vo.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagEvidenceSelectionTest {
    private static JsonNode fixture;
    @BeforeAll static void load() throws Exception {
        try(var input=RagEvidenceSelectionTest.class.getResourceAsStream("/rag/q4-evidence-replay.json")){
            fixture=new ObjectMapper().readTree(input);
        }
    }
    private List<RetrievalHit> hits(JsonNode row) {
        var result=new ArrayList<RetrievalHit>();
        for(var entry:row.path("pool")){
            var c=fixture.path("chunks").path(entry.path("chunk").asText());
            result.add(new RetrievalHit(c.path("chunkSha256").asText(),entry.path("score").asDouble(),1,c.path("filename").asText(),
                    1,1,1,1,"markdown-v1","bounded-v1",c.path("sourceSha256").asText(),c.path("chunkSha256").asText(),
                    c.path("ordinal").asInt(),c.path("start").asInt(),c.path("end").asInt(),c.path("startLine").asInt(),c.path("endLine").asInt(),c.path("text").asText()));
        }
        return result;
    }
    private RetrievalService service(List<RetrievalHit> hits) {
        var transactions=mock(RetrievalTransactions.class);
        var model=mock(EmbeddingGateway.class);
        var vectors=mock(VectorSearch.class);
        var source=new RetrievalSource(1,1,1,1,1,EmbeddingSpec.ID,"synthetic-source","synthetic.md",1,1,"markdown-v1","bounded-v1");
        when(transactions.sources(1,1)).thenReturn(List.of(source));
        when(transactions.begin(eq(1L),eq(1L),anyString(),eq(3))).thenReturn("synthetic-operation");
        when(transactions.modelEnded(anyString(),anyLong())).thenReturn(true);
        when(transactions.finish(anyString(),eq("SUCCEEDED"),isNull(),anyLong())).thenReturn(true);
        when(transactions.snapshot(eq(1L),eq(1L),any())).thenReturn(new RetrievalTransactions.Snapshot(List.of(source),hits));
        when(model.count(anyList())).thenReturn(List.of(3));
        float[] vector=new float[1024];vector[0]=1;
        when(model.embed(anyString(),anyList(),anyList())).thenReturn(Collections.singletonList(vector));
        var candidates=hits.stream().map(h->new VectorCandidate(h.pointId(),h.score(),source.key(),h.ordinal(),h.chunkSha256(),h.sourceSha256())).toList();
        when(vectors.query(eq(1L),eq(1L),eq(EmbeddingSpec.ID),anyList(),any(),anySet(),eq(100))).thenReturn(candidates);
        return new RetrievalService(transactions,model,vectors);
    }
    private boolean covers(List<RetrievalHit> hits,JsonNode target){
        return hits.stream().anyMatch(h->h.sourceSha256().equals(target.path("sourceSha256").asText())
                && h.startLine()<=target.path("startLine").asInt() && h.endLine()>=target.path("endLine").asInt());
    }
    @Test void q4KeepsFiveCandidatesAndIncludesBothFrozenDirectEvidenceSlots(){
        var q4=fixture.path("questions").get(3);var pool=hits(q4);
        assertThat(pool).hasSize(20);
        assertThat(covers(pool.subList(0,5),q4.path("targets").get(0))).isFalse();
        assertThat(pool.get(18).sourceSha256()).isEqualTo(q4.path("targets").get(0).path("sourceSha256").asText());
        var result=service(pool).searchForRag(1,1,q4.path("query").asText());
        assertThat(result.hits()).hasSize(5);assertThat(result.incomplete()).isFalse();
        for(var target:q4.path("targets"))assertThat(covers(result.hits(),target)).as(target.path("slot").asText()).isTrue();
        var provided=new com.devmate.conversation.service.ProjectRagPromptBuilder(new ObjectMapper(),new com.devmate.ai.config.AiProperties())
                .build(new com.devmate.project.vo.ProjectResponse(1L,"Public replay",null,null,null),List.of(),q4.path("query").asText(),result.hits());
        assertThat(provided.sources()).hasSize(5);
        for(var target:q4.path("targets"))assertThat(covers(List.copyOf(provided.sources().values()),target)).as("provided "+target.path("slot").asText()).isTrue();
    }
    @Test void ordinaryRetrievalRetainsOriginalCosineTopFive(){
        var row=fixture.path("questions").get(3);var pool=hits(row);
        assertThat(service(pool).search(1,1,new RetrievalRequest(row.path("query").asText(),5)).hits()).isEqualTo(pool.subList(0,5));
    }
    @Test void remainingNineQuestionsKeepTheirCandidatesAndFrozenCoverage(){
        int original=0,updated=0;
        for(var row:fixture.path("questions")){
            var pool=new ArrayList<>(hits(row));
            // Other runs only saved top five. Arbitrary lower ranks must not disturb full literal coverage.
            if(row.path("number").asInt()!=4)for(int n=5;n<20;n++)pool.add(synthetic(100+n,-0.1-n*0.02,row.path("query").asText()));
            var result=service(pool).searchForRag(1,1,row.path("query").asText());
            if(row.path("number").asInt()!=4)assertThat(result.hits()).as("Q"+row.path("number").asInt()).isEqualTo(pool.subList(0,5));
            for(var target:row.path("targets")){
                if(covers(pool.subList(0,5),target))original++;
                if(covers(result.hits(),target))updated++;
            }
        }
        assertThat(original).isEqualTo(16);assertThat(updated).isEqualTo(17);
    }
    @Test void publicFixtureHasVerifiedChunkHashesAndSeventeenFrozenTargets(){
        assertThat(fixture.path("sources").size()).isEqualTo(7);
        fixture.path("chunks").elements().forEachRemaining(c->assertThat(EmbeddingSpec.sha(c.path("text").asText())).isEqualTo(c.path("chunkSha256").asText()));
        int targets=0;for(var row:fixture.path("questions"))targets+=row.path("targets").size();
        assertThat(targets).isEqualTo(17);
    }
    private RetrievalHit synthetic(int id,double score,String text){
        return new RetrievalHit("point-"+id,score,1,"synthetic.md",1,1,1,1,"v1","v1","source","chunk-"+id,id,0,text.length(),1,1,text);
    }
    private List<RetrievalHit> pool(){
        var result=new ArrayList<RetrievalHit>();for(int n=0;n<20;n++)result.add(synthetic(n,1-n*0.02,"ordinary text"));return result;
    }
    @Test void onlyIndependentCaseSensitiveIdentifiersCountAndRepetitionCannotBoostRank(){
        var pool=pool();pool.set(5,synthetic(5,0.9,"PRECHUNKED CHUNKED_suffix chunked"));pool.set(6,synthetic(6,0.88,"CHUNKED"));
        pool.set(7,synthetic(7,0.86,"CHUNKED ".repeat(20)));
        var result=RagEvidenceSelector.select("是否CHUNKED？",pool);
        assertThat(result).contains(pool.get(6)).doesNotContain(pool.get(5),pool.get(7));
        assertThat(RagEvidenceSelector.select("是否chunked？",pool)).isEqualTo(pool.subList(0,5));
    }
    @Test void poolWindowAndIdentifierCountAreHardBounds(){
        var pool=pool();pool.add(synthetic(20,0.6,"CHUNKED"));
        assertThat(RagEvidenceSelector.select("CHUNKED",pool)).isEqualTo(pool.subList(0,5));
        pool.set(6,synthetic(6,0.88,"AAA BBB CCC DDD EEE FFF GGG HHH III"));
        assertThat(RagEvidenceSelector.select("AAA BBB CCC DDD EEE FFF GGG HHH III",pool)).isEqualTo(pool.subList(0,5));
    }
    @Test void atMostTwoSwapsAndPreviouslyCoveredIdentifiersRemain(){
        var pool=pool();pool.set(4,synthetic(4,0.92,"KEPT"));pool.set(5,synthetic(5,0.9,"AAA"));pool.set(6,synthetic(6,0.88,"BBB"));pool.set(7,synthetic(7,0.86,"CCC"));
        var result=RagEvidenceSelector.select("KEPT AAA BBB CCC",pool);
        assertThat(result).hasSize(5).contains(pool.get(4),pool.get(5),pool.get(6)).doesNotContain(pool.get(7));
        assertThat(result.stream().filter(h->!pool.subList(0,5).contains(h)).count()).isEqualTo(2);
    }
    @Test void sameScoreHasStablePointOrderAndAlreadyCoveredQueriesIgnoreAnyLaterCandidates(){
        var pool=pool();pool.set(0,synthetic(1,1,"CHUNKED"));pool.set(1,synthetic(0,1,"CHUNKED"));
        var expected=new ArrayList<>(pool.subList(0,5));Collections.swap(expected,0,1);
        pool.set(7,synthetic(7,0.86,"CHUNKED"));
        assertThat(RagEvidenceSelector.select("CHUNKED",pool)).isEqualTo(expected);
        Collections.reverse(pool);assertThat(RagEvidenceSelector.select("CHUNKED",pool)).isEqualTo(expected);
    }
}
