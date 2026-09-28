package com.devmate.knowledge.infrastructure;

import com.devmate.knowledge.index.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** Data access only. Parent-free records are the independent cleanup handoff, not cascade children. */
@Repository
public class IndexJournal {
    private final JdbcTemplate jdbc;
    public IndexJournal(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private IndexWork row(ResultSet r, int ignored) throws SQLException {
        return new IndexWork(r.getLong("id"),r.getLong("owner_user_id"),r.getLong("project_id"),r.getLong("document_id"),r.getLong("processing_id"),r.getLong("generation"),
                r.getString("source_sha256"),r.getString("manifest_sha256"),r.getString("spec"),r.getString("state"),r.getBoolean("active"),r.getInt("chunk_count"),r.getLong("tokens"),
                r.getLong("reserved_bytes"),r.getInt("reserved_debts"),r.getBoolean("cleaned"),r.getLong("operation_version"),r.getString("lease_owner"),
                r.getTimestamp("lease_until")==null?null:r.getTimestamp("lease_until").toLocalDateTime(),r.getString("error_code"));
    }
    private IndexWork one(String sql, Object... args) { var rows=jdbc.query(sql,this::row,args); return rows.isEmpty()?null:rows.getFirst(); }
    public IndexWork find(long id) { return one("SELECT * FROM knowledge_indexes WHERE id=?",id); }
    public IndexWork lock(long id) { return one("SELECT * FROM knowledge_indexes WHERE id=? FOR UPDATE",id); }
    public IndexWork latest(long document) { return one("SELECT * FROM knowledge_indexes WHERE document_id=? ORDER BY id DESC LIMIT 1",document); }
    public IndexWork active(long document) { return one("SELECT i.* FROM knowledge_indexes i JOIN knowledge_documents d ON d.active_index_id=i.id JOIN knowledge_processing p ON p.id=i.processing_id "
            + "WHERE d.id=? AND d.storage_state='STORED' AND d.active_processing_id=i.processing_id AND d.sha256=i.source_sha256 AND p.active=1 AND p.state='CHUNKED' AND i.active=1 AND i.state='SUCCEEDED'",document); }
    public List<IndexWork> document(long document) { return jdbc.query("SELECT * FROM knowledge_indexes WHERE document_id=? AND cleaned=0 ORDER BY id LIMIT 4",this::row,document); }
    public int generations(long document) { return jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_indexes WHERE document_id=? AND cleaned=0",Integer.class,document); }
    public boolean inFlight(long document) { return jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_indexes WHERE document_id=? AND state IN ('PENDING','RUNNING')",Integer.class,document)>0; }
    public long insert(long owner,long project,long document,long processing,String sha,String manifest,String spec,int count,long bytes,int debts,LocalDateTime now) {
        long generation=jdbc.queryForObject("SELECT next_index_generation FROM knowledge_documents WHERE id=?",Long.class,document);
        jdbc.update("UPDATE knowledge_documents SET next_index_generation=next_index_generation+1 WHERE id=?",document);
        var key=new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement=connection.prepareStatement("INSERT INTO knowledge_indexes(owner_user_id,project_id,document_id,processing_id,generation,source_sha256,manifest_sha256,spec,state,chunk_count,reserved_bytes,reserved_debts,next_attempt_at,create_time) VALUES(?,?,?,?,?,?,?,?,'PENDING',?,?,?,?,?)",java.sql.Statement.RETURN_GENERATED_KEYS);
            Object[] values={owner,project,document,processing,generation,sha,manifest,spec,count,bytes,debts,now,now};
            for(int i=0;i<values.length;i++) statement.setObject(i+1,values[i]); return statement;
        },key); return java.util.Objects.requireNonNull(key.getKey()).longValue();
    }
    public void insertPoint(long index,IndexPoint point) { jdbc.update("INSERT INTO knowledge_index_points(index_id,ordinal,point_id,chunk_sha256) VALUES(?,?,?,?)",index,point.ordinal(),point.id(),point.sha()); }
    public List<IndexPoint> points(long index,int offset,int limit) { return jdbc.query("SELECT * FROM knowledge_index_points WHERE index_id=? ORDER BY ordinal LIMIT ? OFFSET ?",(r,n)->new IndexPoint(r.getInt("ordinal"),r.getString("point_id"),r.getString("chunk_sha256"),(Integer)r.getObject("tokens"),r.getBoolean("confirmed")),index,limit,offset); }
    public void count(long index,int ordinal,int tokens) { jdbc.update("UPDATE knowledge_index_points SET tokens=? WHERE index_id=? AND ordinal=?",tokens,index,ordinal); }
    public boolean counted(long index,int count) { return jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_index_points WHERE index_id=? AND tokens IS NOT NULL",Integer.class,index)==count; }
    public long expectedTokens(long index) { return jdbc.queryForObject("SELECT COALESCE(SUM(tokens),0) FROM knowledge_index_points WHERE index_id=?",Long.class,index); }
    public int cleanupCursor(long index) {return jdbc.queryForObject("SELECT cleanup_cursor FROM knowledge_indexes WHERE id=?",Integer.class,index);}
    public void cleanupCursor(long index,int cursor) {jdbc.update("UPDATE knowledge_indexes SET cleanup_cursor=? WHERE id=?",cursor,index);}
    public boolean confirmed(long index,int count) { return jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_index_points WHERE index_id=? AND confirmed=1",Integer.class,index)==count; }
    public void confirm(long index,int first,int count) { jdbc.update("UPDATE knowledge_index_points SET confirmed=1 WHERE index_id=? AND ordinal>=? AND ordinal<?",index,first,first+count); }
    public void claim(long id,String lease,LocalDateTime until) { jdbc.update("UPDATE knowledge_indexes SET state='RUNNING',operation_version=operation_version+1,lease_owner=?,lease_until=?,next_attempt_at=? WHERE id=?",lease,until,until,id); }
    public void state(long id,String state,String error,LocalDateTime next) { jdbc.update("UPDATE knowledge_indexes SET state=?,error_code=?,operation_version=operation_version+1,lease_owner=NULL,lease_until=NULL,next_attempt_at=? WHERE id=?",state,error,next,id); }
    public void publish(IndexWork work) { jdbc.update("UPDATE knowledge_indexes SET active=0,state='CLEANUP_REQUIRED',operation_version=operation_version+1,next_attempt_at=CURRENT_TIMESTAMP(6) WHERE document_id=? AND active=1",work.document());
        jdbc.update("UPDATE knowledge_indexes SET state='SUCCEEDED',active=1,lease_owner=NULL,lease_until=NULL,operation_version=operation_version+1 WHERE id=?",work.id());
        jdbc.update("UPDATE knowledge_documents SET active_index_id=? WHERE id=?",work.id(),work.document()); }
    public void cancel(long document,LocalDateTime now) { jdbc.update("UPDATE knowledge_documents SET active_index_id=NULL WHERE id=?",document);
        jdbc.update("UPDATE knowledge_indexes SET active=0,state='CANCELLED',error_code='SOURCE_RETIRED',operation_version=operation_version+1,lease_owner=NULL,lease_until=NULL,next_attempt_at=? WHERE document_id=? AND cleaned=0",now,document); }
    public boolean reserve(long owner,long project,int points,long bytes,int debts) {
        jdbc.update("INSERT INTO knowledge_index_capacity(project_id,owner_user_id) VALUES(?,?) ON DUPLICATE KEY UPDATE project_id=project_id",project,owner);
        // The global row is always locked before the project row, including release paths.
        jdbc.queryForObject("SELECT project_id FROM knowledge_index_capacity WHERE project_id=0 FOR UPDATE",Long.class);
        if(jdbc.update("UPDATE knowledge_index_capacity SET points=points+?,bytes=bytes+?,debts=debts+? WHERE project_id=0 AND points+?<=5000000 AND bytes+?<=21474836480 AND debts+?<=100000",points,bytes,debts,points,bytes,debts)!=1) return false;
        return jdbc.update("UPDATE knowledge_index_capacity SET points=points+?,bytes=bytes+?,debts=debts+? WHERE project_id=? AND owner_user_id=? AND points+?<=500000 AND bytes+?<=2147483648 AND debts+?<=10000",points,bytes,debts,project,owner,points,bytes,debts)==1;
    }
    public boolean reserveTokens(IndexWork work,int tokens,LocalDate day) {
        for(long project: new long[]{0,work.project()}) {
            jdbc.update("INSERT INTO knowledge_index_daily_tokens(project_id,utc_day) VALUES(?,?) ON DUPLICATE KEY UPDATE project_id=project_id",project,day);
            if(jdbc.update("UPDATE knowledge_index_daily_tokens SET tokens=tokens+? WHERE project_id=? AND utc_day=? AND tokens+?<=?",tokens,project,day,tokens,project==0?10000000:2000000)!=1) return false;
        }
        return jdbc.update("UPDATE knowledge_indexes SET tokens=tokens+? WHERE id=? AND tokens+?<=1000000",tokens,work.id(),tokens)==1;
    }
    public void operation(IndexOperation op,LocalDate day) { jdbc.update("INSERT INTO knowledge_index_operations(id,index_id,kind,first_ordinal,point_count,manifest_sha256,tokens,utc_day,state,operation_version) VALUES(?,?,?,?,?,?,?,?,'DISPATCHED',?)",op.id(),op.indexId(),op.kind(),op.first(),op.count(),op.manifest(),op.tokens(),day,op.version()); }
    public List<IndexOperation> operations(long index) { return jdbc.query("SELECT * FROM knowledge_index_operations WHERE index_id=? ORDER BY first_ordinal,kind",(r,n)->new IndexOperation(r.getString("id"),r.getLong("index_id"),r.getString("kind"),r.getInt("first_ordinal"),r.getInt("point_count"),r.getString("manifest_sha256"),r.getInt("tokens"),r.getString("state"),r.getLong("operation_version")),index); }
    public void operationState(String id,String state) { jdbc.update("UPDATE knowledge_index_operations SET state=? WHERE id=?",state,id); }
    public void clean(IndexWork work) {
        jdbc.queryForObject("SELECT project_id FROM knowledge_index_capacity WHERE project_id=0 FOR UPDATE",Long.class);
        for(long project:new long[]{0,work.project()}) jdbc.update("UPDATE knowledge_index_capacity SET points=points-?,bytes=bytes-?,debts=debts-? WHERE project_id=?",work.chunks(),work.bytes(),work.debts(),project);
        jdbc.update("UPDATE knowledge_indexes SET cleaned=1,reserved_bytes=0,reserved_debts=0 WHERE id=?",work.id());
        jdbc.update("UPDATE knowledge_index_requests SET expires_at=CASE WHEN expires_at IS NULL THEN DATE_ADD(accepted_at,INTERVAL 24 HOUR) ELSE expires_at END WHERE index_id=?",work.id());
        jdbc.update("DELETE FROM knowledge_index_operations WHERE index_id=?",work.id()); jdbc.update("DELETE FROM knowledge_index_points WHERE index_id=?",work.id());
    }
    public record Request(long document,long index,String fingerprint,String snapshot) {}
    public void expireRequest(long owner,long project,String uuid,LocalDateTime now) {
        jdbc.update("DELETE FROM knowledge_index_requests WHERE owner_user_id=? AND project_id=? AND client_request_id=? AND expires_at<=?",owner,project,uuid,now);
    }
    public Request request(long owner,long project,String uuid,LocalDateTime now) {
        var rows=jdbc.query("SELECT * FROM knowledge_index_requests WHERE owner_user_id=? AND project_id=? AND client_request_id=? AND (expires_at IS NULL OR expires_at>?)",(r,n)->new Request(r.getLong("document_id"),r.getLong("index_id"),r.getString("fingerprint"),r.getString("snapshot")),owner,project,uuid,now);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public int requestCount(long owner,long project,Long document) { return document==null?jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_index_requests WHERE owner_user_id=? AND project_id=?",Integer.class,owner,project):jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_index_requests WHERE owner_user_id=? AND project_id=? AND document_id=?",Integer.class,owner,project,document); }
    public void bind(IndexWork work,String uuid,String fingerprint,String snapshot,boolean creator,LocalDateTime now) { jdbc.update("INSERT INTO knowledge_index_requests(owner_user_id,project_id,document_id,client_request_id,index_id,fingerprint,creator,accepted_at,expires_at,snapshot) VALUES(?,?,?,?,?,?,?,?,?,?)",work.owner(),work.project(),work.document(),uuid,work.id(),fingerprint,creator,now,creator?null:now.plusHours(24),snapshot); }
    public void snapshots(long index,String snapshot) { jdbc.update("UPDATE knowledge_index_requests SET snapshot=? WHERE index_id=?",snapshot,index); }
    public List<Long> due(LocalDateTime now) { return jdbc.queryForList("SELECT id FROM knowledge_indexes WHERE state IN ('PENDING','RUNNING') AND next_attempt_at<=? ORDER BY next_attempt_at,id LIMIT 20",Long.class,now); }
    public List<Long> cleanup(LocalDateTime now) { return jdbc.queryForList("SELECT id FROM knowledge_indexes WHERE active=0 AND cleaned=0 AND state NOT IN ('PENDING','RUNNING') AND next_attempt_at<=? ORDER BY next_attempt_at,id LIMIT 20",Long.class,now); }
    public void defer(long id,LocalDateTime now) { jdbc.update("UPDATE knowledge_indexes SET next_attempt_at=? WHERE id=?",now.plusMinutes(5),id); }
    public void purge(LocalDateTime now) {
        jdbc.update("DELETE FROM knowledge_index_requests WHERE expires_at<=? ORDER BY expires_at,id LIMIT 20",now);
        for(long id:jdbc.queryForList("SELECT i.id FROM knowledge_indexes i WHERE i.cleaned=1 AND NOT EXISTS(SELECT 1 FROM knowledge_index_requests r WHERE r.index_id=i.id) "
                + "AND (NOT EXISTS(SELECT 1 FROM knowledge_documents d WHERE d.id=i.document_id) OR EXISTS(SELECT 1 FROM knowledge_indexes newer WHERE newer.document_id=i.document_id AND newer.id>i.id)) ORDER BY i.id LIMIT 20",Long.class))
            jdbc.update("DELETE FROM knowledge_indexes WHERE id=? AND cleaned=1 AND NOT EXISTS(SELECT 1 FROM knowledge_index_requests r WHERE r.index_id=knowledge_indexes.id)",id);
        jdbc.update("DELETE FROM knowledge_index_daily_tokens WHERE utc_day<? ORDER BY utc_day,project_id LIMIT 20",now.toLocalDate().minusDays(32));
    }
}
