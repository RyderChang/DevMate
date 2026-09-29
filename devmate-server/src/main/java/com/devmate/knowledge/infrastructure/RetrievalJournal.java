package com.devmate.knowledge.infrastructure;

import com.devmate.ai.embedding.*;
import com.devmate.knowledge.retrieval.*;
import com.devmate.knowledge.vo.RetrievalHit;
import com.devmate.knowledge.application.TextChunker;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Only data access. Query receipts survive deletion and never contain query text or vectors. */
@Repository
public class RetrievalJournal {
    private final JdbcTemplate jdbc;
    public RetrievalJournal(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static final String SOURCE_COLUMNS="i.owner_user_id,i.project_id,i.document_id,i.processing_id,i.id index_id,i.spec,i.source_sha256,d.filename,p.generation processing_generation,i.generation index_generation,p.parser_version,p.strategy_version";
    private static final String SOURCE_JOIN=" FROM knowledge_indexes i JOIN knowledge_documents d ON d.id=i.document_id AND d.active_index_id=i.id AND d.active_processing_id=i.processing_id "
        +"JOIN knowledge_processing p ON p.id=i.processing_id AND p.document_id=d.id AND p.owner_user_id=i.owner_user_id AND p.project_id=i.project_id ";
    private static final String SOURCE_WHERE="i.owner_user_id=? AND i.project_id=? AND i.spec=? AND d.owner_user_id=i.owner_user_id AND d.project_id=i.project_id "
        +"AND d.storage_state='STORED' AND d.sha256=i.source_sha256 AND p.source_sha256=i.source_sha256 AND p.active=1 AND p.state='CHUNKED' "
        +"AND i.active=1 AND i.state='SUCCEEDED' AND i.cleaned=0 AND i.chunk_count=p.chunk_count";
    private RetrievalSource source(java.sql.ResultSet r)throws java.sql.SQLException{
        return new RetrievalSource(r.getLong("owner_user_id"),r.getLong("project_id"),r.getLong("document_id"),r.getLong("processing_id"),r.getLong("index_id"),r.getString("spec"),r.getString("source_sha256"),r.getString("filename"),r.getLong("processing_generation"),r.getLong("index_generation"),r.getString("parser_version"),r.getString("strategy_version"));
    }
    public List<RetrievalSource> sources(long owner,long project,String spec){
        var result=jdbc.query("SELECT "+SOURCE_COLUMNS+SOURCE_JOIN+"WHERE "+SOURCE_WHERE+" ORDER BY d.id LIMIT 101",(r,n)->source(r),owner,project,spec);
        if(result.size()>100)throw new EmbeddingFailure("SOURCE_BOUND_EXCEEDED",true);return result;
    }
    public List<RetrievalHit> hydrate(long owner,long project,String spec,Collection<VectorCandidate> candidates){
        if(candidates.isEmpty())return List.of();if(candidates.size()>200)throw new IllegalArgumentException("Candidate bound exceeded");
        var expected=new HashMap<String,VectorCandidate>();candidates.forEach(c->expected.put(c.pointId(),c));
        var arguments=new ArrayList<Object>(List.of(owner,project,spec));arguments.addAll(expected.keySet());
        String sql="SELECT "+SOURCE_COLUMNS+",k.point_id,k.ordinal,k.chunk_sha256,c.sha256 actual_sha,c.text,c.start_offset,c.end_offset,c.start_line,c.end_line"+SOURCE_JOIN
            +"JOIN knowledge_index_points k ON k.index_id=i.id AND k.confirmed=1 JOIN knowledge_chunks c ON c.processing_id=i.processing_id AND c.document_id=i.document_id "
            +"AND c.owner_user_id=i.owner_user_id AND c.project_id=i.project_id AND c.ordinal=k.ordinal WHERE "+SOURCE_WHERE
            +" AND k.point_id IN ("+String.join(",",Collections.nCopies(expected.size(),"?"))+")";
        return jdbc.query(sql,(r,n)->{
            var s=source(r);var candidate=expected.get(r.getString("point_id"));String text=r.getString("text");String sha=r.getString("chunk_sha256");
            if(!s.key().equals(candidate.sourceKey()) || !s.sourceSha().equals(candidate.sourceSha()) || r.getInt("ordinal")!=candidate.ordinal()
                || !sha.equals(candidate.chunkSha()) || !sha.equals(r.getString("actual_sha")) || !sha.equals(TextChunker.sha(text.getBytes(StandardCharsets.UTF_8))))
                throw new EmbeddingFailure("SOURCE_INTEGRITY_MISMATCH",true);
            return new RetrievalHit(candidate.pointId(),candidate.score(),s.document(),s.filename(),s.processing(),s.index(),s.processingGeneration(),s.indexGeneration(),s.parserVersion(),s.strategyVersion(),s.sourceSha(),sha,candidate.ordinal(),r.getInt("start_offset"),r.getInt("end_offset"),r.getInt("start_line"),r.getInt("end_line"),text);
        },arguments.toArray());
    }
    public List<String> available(long owner, long project, String spec, List<RetrievalHit> snapshots) {
        if (snapshots.size() > 5) throw new IllegalArgumentException("Citation bound exceeded");
        var result = new ArrayList<String>();
        for (var h : snapshots) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*)" + SOURCE_JOIN
                + "JOIN knowledge_index_points k ON k.index_id=i.id AND k.confirmed=1 "
                + "JOIN knowledge_chunks c ON c.processing_id=i.processing_id AND c.document_id=i.document_id "
                + "AND c.owner_user_id=i.owner_user_id AND c.project_id=i.project_id AND c.ordinal=k.ordinal WHERE "
                + SOURCE_WHERE + " AND k.point_id=? AND i.document_id=? AND i.processing_id=? AND i.id=? "
                + "AND p.generation=? AND i.generation=? AND p.parser_version=? AND p.strategy_version=? "
                + "AND i.source_sha256=? AND k.chunk_sha256=? AND c.sha256=? AND k.ordinal=? "
                + "AND c.start_offset=? AND c.end_offset=? AND c.start_line=? AND c.end_line=? AND d.filename=?",
                Integer.class, owner, project, spec, h.pointId(), h.documentId(), h.processingId(), h.indexId(),
                h.processingGeneration(), h.indexGeneration(), h.parserVersion(), h.strategyVersion(),
                h.sourceSha256(), h.chunkSha256(), h.chunkSha256(), h.ordinal(), h.start(), h.end(),
                h.startLine(), h.endLine(), h.filename());
            if (count != null && count == 1) result.add(h.pointId());
        }
        return List.copyOf(result);
    }
    public boolean reserveTokens(long project,int tokens,LocalDate day){
        for(long id:new long[]{0,project}){
            jdbc.update("INSERT INTO knowledge_index_daily_tokens(project_id,utc_day) VALUES(?,?) ON DUPLICATE KEY UPDATE project_id=project_id",id,day);
            if(jdbc.update("UPDATE knowledge_index_daily_tokens SET tokens=tokens+? WHERE project_id=? AND utc_day=? AND tokens+?<=?",tokens,id,day,tokens,id==0?10000000:2000000)!=1)return false;
        }return true;
    }
    public void insert(String id,long owner,long project,String hash,int tokens,LocalDateTime now){
        jdbc.update("INSERT INTO knowledge_retrieval_operations(id,owner_user_id,project_id,spec,query_sha256,tokens,utc_day,state,accepted_at,next_check_at) VALUES(?,?,?,?,?,?,?,'DISPATCHED',?,?)",
            id,owner,project,EmbeddingSpec.ID,hash,tokens,now.toLocalDate(),now,now.plusMinutes(7));
    }
    public void state(String id,String state,String error,long elapsed,LocalDateTime next){
        jdbc.update("UPDATE knowledge_retrieval_operations SET state=?,error_code=?,elapsed_ms=?,next_check_at=? WHERE id=?",state,error,elapsed,next,id);
    }
    public record Operation(String id,long owner,long project,String state,LocalDateTime accepted){}
    private Operation operation(java.sql.ResultSet r,int n)throws java.sql.SQLException{
        return new Operation(r.getString("id"),r.getLong("owner_user_id"),r.getLong("project_id"),r.getString("state"),r.getTimestamp("accepted_at").toLocalDateTime());
    }
    public Operation lock(String id){var result=jdbc.query("SELECT * FROM knowledge_retrieval_operations WHERE id=? FOR UPDATE",this::operation,id);return result.isEmpty()?null:result.getFirst();}
    public List<Operation> due(LocalDateTime now){return jdbc.query("SELECT * FROM knowledge_retrieval_operations WHERE state IN ('DISPATCHED','UNKNOWN','MODEL_ENDED') AND next_check_at<=? ORDER BY next_check_at,id LIMIT 20",this::operation,now);}
    public List<String> expired(LocalDateTime now){return jdbc.queryForList("SELECT id FROM knowledge_retrieval_operations WHERE state IN ('SUCCEEDED','FAILED') AND accepted_at<=? ORDER BY accepted_at,id LIMIT 20",String.class,now.minusHours(24));}
    public void remove(Operation op){
        jdbc.queryForObject("SELECT project_id FROM knowledge_index_capacity WHERE project_id=0 FOR UPDATE",Long.class);
        for(long project:new long[]{0,op.project()})if(jdbc.update("UPDATE knowledge_index_capacity SET bytes=bytes-4096,debts=debts-1 WHERE project_id=? AND bytes>=4096 AND debts>=1",project)!=1)
            throw new IllegalStateException("Retrieval capacity cannot be confirmed");
        jdbc.update("DELETE FROM knowledge_retrieval_operations WHERE id=?",op.id());
    }
}
