package com.devmate.service;

import com.devmate.common.api.ErrorCode;
import com.devmate.common.exception.BusinessException;
import com.devmate.entity.RoleEntity;
import com.devmate.entity.UserRoleEntity;
import com.devmate.mapper.RoleMapper;
import com.devmate.mapper.RolePermissionMapper;
import com.devmate.mapper.UserMapper;
import com.devmate.mapper.UserRoleMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserRoleService {
    public static final String DEFAULT_ROLE_CODE = "USER";

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;

    public UserRoleService(UserMapper userMapper, RoleMapper roleMapper, UserRoleMapper userRoleMapper,
                           RolePermissionMapper rolePermissionMapper) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
    }

    public List<String> findRoleCodes(Long userId) {
        requireUser(userId);
        return userRoleMapper.findRoleCodesByUserId(userId).stream().distinct().sorted().toList();
    }

    public List<String> findPermissionCodes(Long userId) {
        requireUser(userId);
        return rolePermissionMapper.findPermissionCodesByUserId(userId).stream().distinct().sorted().toList();
    }

    @Transactional
    public void assignDefaultRole(Long userId) {
        RoleEntity role = roleMapper.findByCode(DEFAULT_ROLE_CODE);
        if (role == null) {
            throw new BusinessException(ErrorCode.DEFAULT_ROLE_NOT_CONFIGURED);
        }
        assignRole(userId, role);
    }

    @Transactional
    public void assignRole(Long userId, RoleEntity role) {
        requireUser(userId);
        if (userRoleMapper.countAssignment(userId, role.getId()) > 0) {
            return;
        }
        UserRoleEntity assignment = new UserRoleEntity();
        assignment.setUserId(userId);
        assignment.setRoleId(role.getId());
        if (userRoleMapper.insert(assignment) != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private void requireUser(Long userId) {
        if (userId == null || userMapper.selectById(userId) == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
    }
}
