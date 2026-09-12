package com.devmate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devmate.entity.RoleEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RoleMapper extends BaseMapper<RoleEntity> {
    @Select("SELECT id, name, code, description, create_time, update_time FROM `role` WHERE code = #{code}")
    RoleEntity findByCode(@Param("code") String code);
}
