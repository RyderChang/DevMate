package com.devmate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devmate.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
    @Select("SELECT id, username, password, nickname, avatar, role, create_time, update_time "
            + "FROM users WHERE username = #{username}")
    UserEntity findByUsername(@Param("username") String username);
}
