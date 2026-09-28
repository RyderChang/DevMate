package com.devmate.knowledge.infrastructure;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DocumentMapper {
    @Select("SELECT * FROM knowledge_documents WHERE id=#{id}")
    DocumentRow find(long id);

    @Select("SELECT * FROM knowledge_documents WHERE id=#{id} FOR UPDATE")
    DocumentRow lock(long id);

    @Select("SELECT * FROM knowledge_documents WHERE id=#{id} AND owner_user_id=#{owner} AND project_id=#{project} FOR UPDATE")
    DocumentRow lockOwned(@Param("owner") long owner, @Param("project") long project, @Param("id") long id);

    @Select("SELECT * FROM knowledge_documents WHERE owner_user_id=#{owner} AND project_id=#{project} AND id=#{id} "
            + "AND storage_state IN ('UPLOADING','STORED','FAILED')")
    DocumentRow visible(@Param("owner") long owner, @Param("project") long project, @Param("id") long id);

    @Select("SELECT COUNT(*) FROM knowledge_documents WHERE owner_user_id=#{owner} AND project_id=#{project} "
            + "AND storage_state IN ('UPLOADING','STORED','FAILED')")
    long countVisible(@Param("owner") long owner, @Param("project") long project);

    @Select("SELECT * FROM knowledge_documents WHERE owner_user_id=#{owner} AND project_id=#{project} "
            + "AND storage_state IN ('UPLOADING','STORED','FAILED') ORDER BY create_time DESC,id DESC LIMIT #{limit} OFFSET #{offset}")
    List<DocumentRow> visiblePage(@Param("owner") long owner, @Param("project") long project,
                                 @Param("offset") long offset, @Param("limit") int limit);

    @Insert("INSERT INTO knowledge_project_capacity(project_id,owner_user_id) VALUES(#{project},#{owner}) "
            + "ON DUPLICATE KEY UPDATE project_id=project_id")
    void ensureCapacity(@Param("owner") long owner, @Param("project") long project);

    @Update("UPDATE knowledge_project_capacity SET reserved_documents=reserved_documents+1,reserved_bytes=reserved_bytes+#{bytes} "
            + "WHERE project_id=#{project} AND owner_user_id=#{owner} AND reserved_documents<#{maxCount} "
            + "AND reserved_bytes+#{bytes}<=#{maxBytes}")
    int reserveCapacity(@Param("owner") long owner, @Param("project") long project, @Param("bytes") long bytes,
                        @Param("maxCount") int maxCount, @Param("maxBytes") long maxBytes);

    @Update("UPDATE knowledge_project_capacity SET reserved_documents=reserved_documents-1,reserved_bytes=reserved_bytes-#{bytes} "
            + "WHERE project_id=#{project} AND owner_user_id=#{owner} AND reserved_documents>0 AND reserved_bytes>=#{bytes}")
    int releaseCapacity(@Param("owner") long owner, @Param("project") long project, @Param("bytes") long bytes);

    @Insert("INSERT INTO knowledge_documents(owner_user_id,project_id,filename,file_type,byte_size,sha256,source_type, "
            + "bucket,object_key,put_token,storage_state,remote_phase,operation_version,lease_owner,lease_until,next_attempt_at,create_time,update_time) "
            + "VALUES(#{ownerUserId},#{projectId},#{filename},#{fileType},#{byteSize},#{sha256},#{sourceType},#{bucket},#{objectKey}, "
            + "#{putToken},#{storageState},#{remotePhase},#{operationVersion},#{leaseOwner},#{leaseUntil},#{nextAttemptAt},#{createTime},#{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(DocumentRow document);

    @Update("UPDATE knowledge_documents SET object_key=#{objectKey} WHERE id=#{id} AND remote_phase='NOT_STARTED'")
    int setLocator(DocumentRow document);

    @Select("SELECT document_id,fingerprint,terminal_state FROM knowledge_document_requests "
            + "WHERE owner_user_id=#{owner} AND project_id=#{project} AND client_request_id=#{request}")
    DocumentRequestRow request(@Param("owner") long owner, @Param("project") long project, @Param("request") String request);

    @Delete("DELETE FROM knowledge_document_requests WHERE owner_user_id=#{owner} AND project_id=#{project} "
            + "AND client_request_id=#{request} AND document_id IS NULL AND expires_at<=#{now}")
    void expireRequest(@Param("owner") long owner, @Param("project") long project,
                       @Param("request") String request, @Param("now") LocalDateTime now);

    @Insert("INSERT INTO knowledge_document_requests(owner_user_id,project_id,client_request_id,fingerprint,document_id) "
            + "VALUES(#{owner},#{project},#{request},#{fingerprint},#{id})")
    void insertRequest(@Param("owner") long owner, @Param("project") long project, @Param("request") String request,
                       @Param("fingerprint") String fingerprint, @Param("id") long id);

    @Update("UPDATE knowledge_documents SET storage_state=#{row.storageState},remote_phase=#{row.remotePhase}, "
            + "error_code=#{row.errorCode},operation_version=#{row.operationVersion},lease_owner=#{row.leaseOwner},lease_until=#{row.leaseUntil}, "
            + "retry_count=#{row.retryCount},next_attempt_at=#{row.nextAttemptAt},needs_manual=#{row.needsManual},update_time=#{row.updateTime} "
            + "WHERE id=#{row.id} AND storage_state=#{previousState} AND operation_version=#{previousVersion}")
    int save(@Param("row") DocumentRow row, @Param("previousState") String previousState, @Param("previousVersion") long previousVersion);

    @Select("SELECT id FROM knowledge_documents WHERE needs_manual=0 AND storage_state IN ('UPLOADING','FAILED','DELETE_PENDING') "
            + "AND next_attempt_at<=#{now} AND (lease_until IS NULL OR lease_until<=#{now}) ORDER BY next_attempt_at,id LIMIT #{limit}")
    List<Long> due(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Select("SELECT id FROM knowledge_documents WHERE owner_user_id=#{owner} AND project_id=#{project} "
            + "AND storage_state<>'DELETE_PENDING' ORDER BY id LIMIT #{limit}")
    List<Long> projectCleanupCandidates(@Param("owner") long owner, @Param("project") long project, @Param("limit") int limit);

    @Update("UPDATE knowledge_document_requests SET document_id=NULL,terminal_state=#{terminal},expires_at=#{expires} WHERE document_id=#{id}")
    int terminateRequest(@Param("id") long id, @Param("terminal") String terminal, @Param("expires") LocalDateTime expires);

    @Delete("DELETE FROM knowledge_documents WHERE id=#{id} AND operation_version=#{version} AND storage_state=#{state} AND remote_phase='FINISHED'")
    int remove(@Param("id") long id, @Param("version") long version, @Param("state") String state);

    @Delete("DELETE FROM knowledge_document_requests WHERE document_id IS NULL AND expires_at<=#{now} ORDER BY expires_at,id LIMIT #{limit}")
    int purgeExpired(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
