package com.devmate.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devmate.project.entity.ProjectEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProjectMapper extends BaseMapper<ProjectEntity> {

    @Insert("INSERT INTO projects(owner_user_id, name, description) "
            + "VALUES(#{ownerUserId}, #{name}, #{description})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertProject(ProjectEntity project);

    @Select("SELECT id, owner_user_id, name, description, deleted, create_time, update_time, delete_time "
            + "FROM projects WHERE id=#{projectId} AND owner_user_id=#{ownerUserId} AND deleted=0")
    ProjectEntity findOwnedActiveById(@Param("projectId") Long projectId,
                                      @Param("ownerUserId") Long ownerUserId);

    @Select("SELECT COUNT(*) FROM projects WHERE owner_user_id=#{ownerUserId} AND deleted=0")
    long countOwnedActive(@Param("ownerUserId") Long ownerUserId);

    @Select("SELECT id, owner_user_id, name, description, deleted, create_time, update_time, delete_time "
            + "FROM projects WHERE owner_user_id=#{ownerUserId} AND deleted=0 "
            + "ORDER BY update_time DESC, id DESC LIMIT #{pageSize} OFFSET #{offset}")
    List<ProjectEntity> findOwnedActivePage(@Param("ownerUserId") Long ownerUserId,
                                            @Param("offset") long offset,
                                            @Param("pageSize") int pageSize);

    @Update("UPDATE projects SET name=#{name}, description=#{description}, "
            + "update_time=CURRENT_TIMESTAMP(6) "
            + "WHERE id=#{projectId} AND owner_user_id=#{ownerUserId} AND deleted=0")
    int updateOwnedActive(@Param("projectId") Long projectId,
                          @Param("ownerUserId") Long ownerUserId,
                          @Param("name") String name,
                          @Param("description") String description);

    @Update("UPDATE projects SET deleted=1, delete_time=CURRENT_TIMESTAMP(6), "
            + "update_time=CURRENT_TIMESTAMP(6) "
            + "WHERE id=#{projectId} AND owner_user_id=#{ownerUserId} AND deleted=0")
    int softDeleteOwnedActive(@Param("projectId") Long projectId,
                              @Param("ownerUserId") Long ownerUserId);
}
