package com.devmate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devmate.entity.UserRoleEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserRoleMapper extends BaseMapper<UserRoleEntity> {
    @Select("SELECT DISTINCT r.code FROM user_role ur JOIN `role` r ON r.id = ur.role_id "
            + "WHERE ur.user_id = #{userId} ORDER BY r.code")
    List<String> findRoleCodesByUserId(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM user_role WHERE user_id = #{userId} AND role_id = #{roleId}")
    int countAssignment(@Param("userId") Long userId, @Param("roleId") Long roleId);
}
