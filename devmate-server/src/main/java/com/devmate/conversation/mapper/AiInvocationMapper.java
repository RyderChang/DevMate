package com.devmate.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devmate.conversation.entity.AiInvocationEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AiInvocationMapper extends BaseMapper<AiInvocationEntity> {
    @Insert("INSERT INTO ai_invocations(conversation_id,client_request_id,user_message_id,provider,model,"
            + "prompt_template_version,status,started_at) VALUES(#{conversationId},#{clientRequestId},"
            + "#{userMessageId},#{provider},#{model},#{promptTemplateVersion},#{status},#{startedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertInvocation(AiInvocationEntity invocation);

    @Select("SELECT i.* FROM ai_invocations i JOIN conversations c ON c.id=i.conversation_id "
            + "JOIN projects p ON p.id=c.project_id AND p.owner_user_id=c.owner_user_id "
            + "WHERE i.conversation_id=#{conversationId} AND i.client_request_id=#{clientRequestId} "
            + "AND c.project_id=#{projectId} AND c.owner_user_id=#{ownerUserId} AND p.deleted=0")
    AiInvocationEntity findOwnedByRequest(@Param("ownerUserId") Long ownerUserId,
                                           @Param("projectId") Long projectId,
                                           @Param("conversationId") Long conversationId,
                                           @Param("clientRequestId") String clientRequestId);

    @Update("UPDATE ai_invocations SET status='FAILED',error_code=#{errorCode},completed_at=#{completedAt} "
            + "WHERE conversation_id=#{conversationId} AND status='PENDING'")
    int failPendingForConversation(@Param("conversationId") Long conversationId,
                                   @Param("errorCode") String errorCode,
                                   @Param("completedAt") LocalDateTime completedAt);

    @Update("UPDATE ai_invocations SET assistant_message_id=#{assistantMessageId},provider_request_id=#{providerRequestId},"
            + "status='SUCCEEDED',input_tokens=#{inputTokens},output_tokens=#{outputTokens},total_tokens=#{totalTokens},"
            + "duration_ms=#{durationMs},completed_at=#{completedAt} WHERE id=#{id} AND status='PENDING'")
    int succeed(@Param("id") Long id, @Param("assistantMessageId") Long assistantMessageId,
                @Param("providerRequestId") String providerRequestId,
                @Param("inputTokens") Integer inputTokens, @Param("outputTokens") Integer outputTokens,
                @Param("totalTokens") Integer totalTokens, @Param("durationMs") long durationMs,
                @Param("completedAt") LocalDateTime completedAt);

    @Update("UPDATE ai_invocations SET status='FAILED',error_code=#{errorCode},duration_ms=#{durationMs},"
            + "completed_at=#{completedAt} WHERE id=#{id} AND status='PENDING'")
    int fail(@Param("id") Long id, @Param("errorCode") String errorCode,
             @Param("durationMs") long durationMs, @Param("completedAt") LocalDateTime completedAt);
}
