package com.devmate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devmate.entity.RolePermissionEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RolePermissionMapper extends BaseMapper<RolePermissionEntity> {
    @Select("SELECT DISTINCT p.code FROM user_role ur "
            + "JOIN role_permission rp ON rp.role_id = ur.role_id "
            + "JOIN `permission` p ON p.id = rp.permission_id "
            + "WHERE ur.user_id = #{userId} ORDER BY p.code")
    List<String> findPermissionCodesByUserId(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM role_permission WHERE role_id = #{roleId} AND permission_id = #{permissionId}")
    int countRelation(@Param("roleId") Long roleId, @Param("permissionId") Long permissionId);
}
