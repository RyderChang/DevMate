package com.devmate.service;

import com.devmate.common.api.ErrorCode;
import com.devmate.common.exception.BusinessException;
import com.devmate.entity.RoleEntity;
import com.devmate.entity.UserEntity;
import com.devmate.entity.UserRoleEntity;
import com.devmate.mapper.RoleMapper;
import com.devmate.mapper.RolePermissionMapper;
import com.devmate.mapper.UserMapper;
import com.devmate.mapper.UserRoleMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserRoleServiceTest {
    private final UserMapper userMapper = mock(UserMapper.class);
    private final RoleMapper roleMapper = mock(RoleMapper.class);
    private final UserRoleMapper userRoleMapper = mock(UserRoleMapper.class);
    private final RolePermissionMapper rolePermissionMapper = mock(RolePermissionMapper.class);
    private UserRoleService service;

    @BeforeEach
    void setUp() {
        service = new UserRoleService(userMapper, roleMapper, userRoleMapper, rolePermissionMapper);
    }

    @Test
    void returnsStableDistinctRolesAndEmptyCollection() {
        when(userMapper.selectById(7L)).thenReturn(new UserEntity());
        when(userRoleMapper.findRoleCodesByUserId(7L)).thenReturn(List.of("USER", "ADMIN", "USER"));
        assertThat(service.findRoleCodes(7L)).containsExactly("ADMIN", "USER");
        when(userRoleMapper.findRoleCodesByUserId(7L)).thenReturn(List.of());
        assertThat(service.findRoleCodes(7L)).isEmpty();
    }

    @Test
    void rejectsUnknownUser() {
        assertThatThrownBy(() -> service.findRoleCodes(99L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.USER_NOT_FOUND.getCode()));
    }

    @Test
    void assignsDefaultRoleAndFailsWhenConfigurationIsMissing() {
        UserEntity user = new UserEntity();
        when(userMapper.selectById(7L)).thenReturn(user);
        RoleEntity role = new RoleEntity();
        role.setId(2L);
        when(roleMapper.findByCode("USER")).thenReturn(role);
        when(userRoleMapper.insert(org.mockito.ArgumentMatchers.any(UserRoleEntity.class))).thenReturn(1);
        service.assignDefaultRole(7L);
        verify(userRoleMapper).insert(org.mockito.ArgumentMatchers.argThat(
                (UserRoleEntity assignment) -> assignment.getUserId().equals(7L)
                        && assignment.getRoleId().equals(2L)));

        when(roleMapper.findByCode("USER")).thenReturn(null);
        assertThatThrownBy(() -> service.assignDefaultRole(7L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.DEFAULT_ROLE_NOT_CONFIGURED.getCode()));
    }
}
