package com.devmate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devmate.entity.PermissionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PermissionMapper extends BaseMapper<PermissionEntity> {
    @Select("SELECT id, name, code, description, create_time, update_time FROM `permission` WHERE code = #{code}")
    PermissionEntity findByCode(@Param("code") String code);
}
