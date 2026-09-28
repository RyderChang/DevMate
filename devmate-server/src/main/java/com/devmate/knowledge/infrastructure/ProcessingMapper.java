package com.devmate.knowledge.infrastructure;

import com.devmate.knowledge.application.TextChunk;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ProcessingMapper {
    @Select("SELECT * FROM knowledge_processing WHERE id=#{id}") ProcessingRow find(long id);
    @Select("SELECT * FROM knowledge_processing WHERE id=#{id} FOR UPDATE") ProcessingRow lock(long id);
    @Select("SELECT * FROM knowledge_processing WHERE document_id=#{id} ORDER BY generation DESC LIMIT 1") ProcessingRow latest(long id);
    @Select("SELECT p.* FROM knowledge_processing p JOIN knowledge_documents d ON d.active_processing_id=p.id "
            + "WHERE d.id=#{id} AND d.storage_state='STORED' AND p.active=1 AND p.state='CHUNKED' AND p.source_sha256=d.sha256") ProcessingRow active(long id);
    @Select("SELECT p.* FROM knowledge_processing p JOIN knowledge_documents d ON d.active_processing_id=p.id WHERE d.id=#{id}")
    ProcessingRow current(long id);
    @Select("SELECT COUNT(*) FROM knowledge_processing WHERE document_id=#{id} AND state IN ('PENDING','PROCESSING')") int inFlight(long id);
    @Select("SELECT COUNT(*) FROM knowledge_processing WHERE document_id=#{id} "
            + "AND (active=1 OR state IN ('PENDING','PROCESSING') OR used_bytes>0 OR reserved_bytes>0)") int generations(long id);
    @Select("SELECT COUNT(*) FROM knowledge_processing WHERE document_id=#{id} "
            + "AND active=0 AND state NOT IN ('PENDING','PROCESSING') AND (used_bytes>0 OR reserved_bytes>0)") int residual(long id);
    @Insert("INSERT INTO knowledge_processing_capacity(project_id,owner_user_id) VALUES(#{project},#{owner}) ON DUPLICATE KEY UPDATE project_id=project_id")
    void ensureCapacity(@Param("owner") long owner, @Param("project") long project);
    @Update("UPDATE knowledge_processing_capacity SET charged_bytes=charged_bytes+#{bytes} "
            + "WHERE project_id=#{project} AND owner_user_id=#{owner} AND charged_bytes+#{bytes}<=268435456")
    int reserve(@Param("owner") long owner, @Param("project") long project, @Param("bytes") long bytes);
    @Update("UPDATE knowledge_processing_capacity SET charged_bytes=charged_bytes-#{bytes} "
            + "WHERE project_id=#{project} AND owner_user_id=#{owner} AND charged_bytes>=#{bytes}")
    int release(@Param("owner") long owner, @Param("project") long project, @Param("bytes") long bytes);
    @Select("SELECT * FROM knowledge_processing_requests WHERE owner_user_id=#{owner} AND project_id=#{project} AND client_request_id=#{request}")
    ProcessingRequestRow request(@Param("owner") long owner, @Param("project") long project, @Param("request") String request);
    @Select("SELECT COUNT(*) FROM knowledge_processing_requests WHERE owner_user_id=#{owner} AND project_id=#{project}")
    int projectRequests(@Param("owner") long owner, @Param("project") long project);
    @Select("SELECT COUNT(*) FROM knowledge_processing_requests WHERE owner_user_id=#{owner} AND project_id=#{project} AND document_id=#{id}")
    int documentRequests(@Param("owner") long owner, @Param("project") long project, @Param("id") long id);
    @Insert("INSERT INTO knowledge_processing(owner_user_id,project_id,document_id,generation,source_sha256,parser_version,strategy_version,"
            + "state,reserved_bytes,next_attempt_at,create_time,update_time) VALUES(#{ownerUserId},#{projectId},#{documentId},#{generation},"
            + "#{sourceSha256},#{parserVersion},#{strategyVersion},#{state},#{reservedBytes},#{nextAttemptAt},#{createTime},#{updateTime})")
    @Options(useGeneratedKeys=true, keyProperty="id") int insert(ProcessingRow row);
    @Insert("INSERT INTO knowledge_processing_requests(owner_user_id,project_id,document_id,client_request_id,fingerprint,processing_id,"
            + "generation,source_sha256,parser_version,strategy_version,terminal_state,creator,expires_at) VALUES(#{ownerUserId},#{projectId},#{documentId},"
            + "#{clientRequestId},#{fingerprint},#{processingId},#{generation},#{sourceSha256},#{parserVersion},#{strategyVersion},#{terminalState},#{creator},#{expiresAt})")
    int insertRequest(ProcessingRequestRow row);
    @Select("SELECT next_processing_generation FROM knowledge_documents WHERE id=#{id}") long nextGeneration(long id);
    @Update("UPDATE knowledge_documents SET next_processing_generation=next_processing_generation+1 WHERE id=#{id}") int advanceGeneration(long id);
    @Update("UPDATE knowledge_documents SET active_processing_id=#{processing} WHERE id=#{id}")
    int setActive(@Param("id") long id, @Param("processing") Long processing);
    @Update("UPDATE knowledge_processing SET normalized_sha256=#{row.normalizedSha256},state=#{row.state},active=#{row.active},"
            + "chunk_count=#{row.chunkCount},text_bytes=#{row.textBytes},used_bytes=#{row.usedBytes},reserved_bytes=#{row.reservedBytes},"
            + "operation_version=#{row.operationVersion},lease_owner=#{row.leaseOwner},lease_until=#{row.leaseUntil},retry_count=#{row.retryCount},attempt_count=#{row.attemptCount},"
            + "next_attempt_at=#{row.nextAttemptAt},error_code=#{row.errorCode},update_time=#{row.updateTime} WHERE id=#{row.id} AND operation_version=#{version}")
    int save(@Param("row") ProcessingRow row, @Param("version") long version);
    @Insert("<script>INSERT INTO knowledge_chunks(owner_user_id,project_id,document_id,processing_id,ordinal,start_offset,end_offset,start_line,end_line,text,sha256,byte_size) VALUES "
            + "<foreach collection='chunks' item='chunk' separator=','>(#{row.ownerUserId},#{row.projectId},#{row.documentId},#{row.id},#{chunk.ordinal},#{chunk.start},#{chunk.end},"
            + "#{chunk.startLine},#{chunk.endLine},#{chunk.text},#{chunk.sha256},#{chunk.byteSize})</foreach></script>")
    int insertChunks(@Param("row") ProcessingRow row, @Param("chunks") List<TextChunk> chunks);
    @Select("SELECT ordinal,start_offset AS start,end_offset AS end,start_line,end_line,text,sha256,byte_size FROM knowledge_chunks "
            + "WHERE processing_id=#{id} ORDER BY ordinal LIMIT #{limit} OFFSET #{offset}")
    List<TextChunk> chunks(@Param("id") long id, @Param("offset") int offset, @Param("limit") int limit);
    @Select("SELECT COUNT(*) FROM knowledge_chunks WHERE processing_id=#{id}") int countChunks(long id);
    @Delete("DELETE FROM knowledge_chunks WHERE processing_id=#{id} ORDER BY ordinal LIMIT #{limit}")
    int deleteChunks(@Param("id") long id, @Param("limit") int limit);
    @Select("SELECT id FROM knowledge_processing WHERE state IN ('PENDING','PROCESSING') AND next_attempt_at<=#{now} "
            + "AND (lease_until IS NULL OR lease_until<=#{now}) ORDER BY next_attempt_at,id LIMIT 20") List<Long> due(LocalDateTime now);
    @Select("SELECT id FROM knowledge_processing WHERE active=0 AND state IN ('CHUNKED','FAILED','CANCELLED') "
            + "AND (used_bytes>0 OR reserved_bytes>0 OR EXISTS(SELECT 1 FROM knowledge_processing_requests r WHERE r.processing_id=knowledge_processing.id AND r.creator=1 AND r.expires_at IS NULL)) "
            + "ORDER BY id LIMIT 20") List<Long> cleanupCandidates();
    @Select("SELECT id FROM knowledge_processing WHERE state='PROCESSING' ORDER BY id LIMIT 20") List<Long> processingCandidates();
    @Select("SELECT id FROM knowledge_processing WHERE document_id=#{id} ORDER BY id") List<Long> documentRecords(long id);
    @Update("UPDATE knowledge_processing_requests SET terminal_state=#{row.state},error_code=#{row.errorCode},"
            + "expires_at=CASE WHEN creator=1 AND expires_at IS NULL THEN #{expires} ELSE expires_at END WHERE processing_id=#{row.id}")
    void terminateRequests(@Param("row") ProcessingRow row, @Param("expires") LocalDateTime expires);
    @Update("UPDATE knowledge_processing_requests SET processing_id=NULL WHERE document_id=#{id}") void detachRequests(long id);
    @Delete("DELETE FROM knowledge_processing WHERE document_id=#{id}") int removeDocumentRecords(long id);
    @Select("SELECT id FROM knowledge_processing_requests WHERE expires_at<=#{now} ORDER BY expires_at,id LIMIT 20") List<Long> expiredRequests(LocalDateTime now);
    @Select("SELECT * FROM knowledge_processing_requests WHERE id=#{id} FOR UPDATE") ProcessingRequestRow lockRequest(long id);
    @Delete("DELETE FROM knowledge_processing_requests WHERE id=#{id} AND expires_at<=#{now}")
    int removeRequest(@Param("id") long id, @Param("now") LocalDateTime now);
    @Select("SELECT * FROM knowledge_processing_requests WHERE id=#{id}") ProcessingRequestRow findRequest(long id);
    @Delete("DELETE p FROM knowledge_processing p WHERE p.document_id=#{id} AND p.active=0 AND p.state IN ('FAILED','CANCELLED','CHUNKED') "
            + "AND p.used_bytes=0 AND p.reserved_bytes=0 AND p.id<>#{latest} AND NOT EXISTS(SELECT 1 FROM knowledge_processing_requests r WHERE r.processing_id=p.id)")
    int prune(@Param("id") long id, @Param("latest") long latest);
}
