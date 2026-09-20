package com.devmate.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devmate.conversation.entity.ConversationMessageEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ConversationMessageMapper extends BaseMapper<ConversationMessageEntity> {
    @Insert("INSERT INTO conversation_messages(conversation_id,sequence_no,role,content,create_time) "
            + "VALUES(#{conversationId},#{sequenceNo},#{role},#{content},#{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertMessage(ConversationMessageEntity message);

    @Select("SELECT COALESCE(MAX(sequence_no),0)+1 FROM conversation_messages WHERE conversation_id=#{conversationId}")
    long nextSequence(@Param("conversationId") Long conversationId);

    @Select("SELECT m.id,m.conversation_id,m.sequence_no,m.role,m.content,m.create_time "
            + "FROM conversation_messages m JOIN conversations c ON c.id=m.conversation_id "
            + "JOIN projects p ON p.id=c.project_id AND p.owner_user_id=c.owner_user_id "
            + "WHERE m.id=#{messageId} AND c.id=#{conversationId} AND c.project_id=#{projectId} "
            + "AND c.owner_user_id=#{ownerUserId} AND p.deleted=0")
    ConversationMessageEntity findOwnedById(@Param("ownerUserId") Long ownerUserId,
                                             @Param("projectId") Long projectId,
                                             @Param("conversationId") Long conversationId,
                                             @Param("messageId") Long messageId);

    @Select("SELECT COUNT(*) FROM conversation_messages m JOIN conversations c ON c.id=m.conversation_id "
            + "JOIN projects p ON p.id=c.project_id AND p.owner_user_id=c.owner_user_id "
            + "WHERE c.id=#{conversationId} AND c.project_id=#{projectId} "
            + "AND c.owner_user_id=#{ownerUserId} AND p.deleted=0")
    long countOwned(@Param("ownerUserId") Long ownerUserId, @Param("projectId") Long projectId,
                    @Param("conversationId") Long conversationId);

    @Select("SELECT m.id,m.conversation_id,m.sequence_no,m.role,m.content,m.create_time "
            + "FROM conversation_messages m JOIN conversations c ON c.id=m.conversation_id "
            + "JOIN projects p ON p.id=c.project_id AND p.owner_user_id=c.owner_user_id "
            + "WHERE c.id=#{conversationId} AND c.project_id=#{projectId} "
            + "AND c.owner_user_id=#{ownerUserId} AND p.deleted=0 "
            + "ORDER BY m.sequence_no ASC,m.id ASC LIMIT #{pageSize} OFFSET #{offset}")
    List<ConversationMessageEntity> findOwnedPage(@Param("ownerUserId") Long ownerUserId,
                                                   @Param("projectId") Long projectId,
                                                   @Param("conversationId") Long conversationId,
                                                   @Param("offset") long offset,
                                                   @Param("pageSize") int pageSize);

    @Select("SELECT m.id,m.conversation_id,m.sequence_no,m.role,m.content,m.create_time "
            + "FROM conversation_messages m JOIN conversations c ON c.id=m.conversation_id "
            + "JOIN projects p ON p.id=c.project_id AND p.owner_user_id=c.owner_user_id "
            + "WHERE c.id=#{conversationId} AND c.project_id=#{projectId} "
            + "AND c.owner_user_id=#{ownerUserId} AND p.deleted=0 AND "
            + "(m.role='ASSISTANT' OR EXISTS (SELECT 1 FROM ai_invocations i "
            + "WHERE i.user_message_id=m.id AND i.status='SUCCEEDED')) "
            + "ORDER BY m.sequence_no DESC,m.id DESC LIMIT #{limit}")
    List<ConversationMessageEntity> findRecentSuccessful(@Param("ownerUserId") Long ownerUserId,
                                                          @Param("projectId") Long projectId,
                                                          @Param("conversationId") Long conversationId,
                                                          @Param("limit") int limit);
}
