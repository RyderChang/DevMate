package com.devmate.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devmate.conversation.entity.ConversationEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {
    String OWNED_ACTIVE_SELECT = "SELECT c.id,c.project_id,c.owner_user_id,c.title,c.generation_state,"
            + "c.generation_started_at,c.create_time,c.update_time FROM conversations c "
            + "JOIN projects p ON p.id=c.project_id AND p.owner_user_id=c.owner_user_id "
            + "WHERE c.id=#{conversationId} AND c.project_id=#{projectId} "
            + "AND c.owner_user_id=#{ownerUserId} AND p.deleted=0";

    @Insert("INSERT INTO conversations(project_id,owner_user_id,title) "
            + "VALUES(#{projectId},#{ownerUserId},#{title})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertConversation(ConversationEntity conversation);

    @Select(OWNED_ACTIVE_SELECT)
    ConversationEntity findOwnedActive(@Param("ownerUserId") Long ownerUserId,
                                        @Param("projectId") Long projectId,
                                        @Param("conversationId") Long conversationId);

    @Select(OWNED_ACTIVE_SELECT + " FOR UPDATE")
    ConversationEntity lockOwnedActive(@Param("ownerUserId") Long ownerUserId,
                                        @Param("projectId") Long projectId,
                                        @Param("conversationId") Long conversationId);

    @Select("SELECT id,project_id,owner_user_id,title,generation_state,generation_started_at,"
            + "create_time,update_time FROM conversations WHERE id=#{conversationId} "
            + "AND project_id=#{projectId} AND owner_user_id=#{ownerUserId} FOR UPDATE")
    ConversationEntity lockOwned(@Param("ownerUserId") Long ownerUserId,
                                 @Param("projectId") Long projectId,
                                 @Param("conversationId") Long conversationId);

    @Select("SELECT COUNT(*) FROM conversations c JOIN projects p "
            + "ON p.id=c.project_id AND p.owner_user_id=c.owner_user_id "
            + "WHERE c.project_id=#{projectId} AND c.owner_user_id=#{ownerUserId} AND p.deleted=0")
    long countOwnedActive(@Param("ownerUserId") Long ownerUserId, @Param("projectId") Long projectId);

    @Select("SELECT c.id,c.project_id,c.owner_user_id,c.title,c.generation_state,"
            + "c.generation_started_at,c.create_time,c.update_time FROM conversations c "
            + "JOIN projects p ON p.id=c.project_id AND p.owner_user_id=c.owner_user_id "
            + "WHERE c.project_id=#{projectId} AND c.owner_user_id=#{ownerUserId} AND p.deleted=0 "
            + "ORDER BY c.update_time DESC,c.id DESC LIMIT #{pageSize} OFFSET #{offset}")
    List<ConversationEntity> findOwnedActivePage(@Param("ownerUserId") Long ownerUserId,
                                                  @Param("projectId") Long projectId,
                                                  @Param("offset") long offset,
                                                  @Param("pageSize") int pageSize);

    @Update("UPDATE conversations SET generation_state='GENERATING',generation_started_at=#{startedAt} "
            + "WHERE id=#{conversationId} AND project_id=#{projectId} AND owner_user_id=#{ownerUserId} "
            + "AND generation_state='IDLE' AND generation_started_at IS NULL")
    int startGeneration(@Param("ownerUserId") Long ownerUserId, @Param("projectId") Long projectId,
                        @Param("conversationId") Long conversationId,
                        @Param("startedAt") LocalDateTime startedAt);

    @Update("UPDATE conversations SET generation_state='IDLE',generation_started_at=NULL "
            + "WHERE id=#{conversationId} AND project_id=#{projectId} AND owner_user_id=#{ownerUserId} "
            + "AND generation_state='GENERATING' AND generation_started_at=#{startedAt}")
    int releaseGeneration(@Param("ownerUserId") Long ownerUserId, @Param("projectId") Long projectId,
                          @Param("conversationId") Long conversationId,
                          @Param("startedAt") LocalDateTime startedAt);

    @Update("UPDATE conversations SET generation_state='IDLE',generation_started_at=NULL,update_time=#{completedAt} "
            + "WHERE id=#{conversationId} AND project_id=#{projectId} AND owner_user_id=#{ownerUserId} "
            + "AND generation_state='GENERATING' AND generation_started_at=#{startedAt}")
    int completeGeneration(@Param("ownerUserId") Long ownerUserId, @Param("projectId") Long projectId,
                           @Param("conversationId") Long conversationId,
                           @Param("startedAt") LocalDateTime startedAt,
                           @Param("completedAt") LocalDateTime completedAt);
}
