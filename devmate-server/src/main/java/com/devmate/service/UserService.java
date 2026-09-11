package com.devmate.service;

import com.devmate.common.api.ErrorCode;
import com.devmate.common.exception.BusinessException;
import com.devmate.dto.LoginRequest;
import com.devmate.dto.RegisterRequest;
import com.devmate.entity.UserEntity;
import com.devmate.mapper.UserMapper;
import com.devmate.security.CurrentUser;
import com.devmate.security.JwtService;
import com.devmate.vo.LoginResponse;
import com.devmate.vo.UserResponse;
import java.sql.SQLIntegrityConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private static final String DEFAULT_ROLE = "USER";
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public UserService(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String username = request.username().trim();
        if (userMapper.findByUsername(username) != null) {
            throw new BusinessException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setNickname(normalizeNickname(request.nickname(), username));
        user.setRole(DEFAULT_ROLE);
        try {
            userMapper.insert(user);
        } catch (DataIntegrityViolationException exception) {
            if (hasUniqueConstraintCause(exception)) {
                throw new BusinessException(ErrorCode.USERNAME_ALREADY_EXISTS, ErrorCode.USERNAME_ALREADY_EXISTS.getMessage(), exception);
            }
            throw exception;
        }
        return toResponse(user);
    }

    public LoginResponse login(LoginRequest request) {
        UserEntity user = userMapper.findByUsername(request.username().trim());
        if (user == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        CurrentUser principal = new CurrentUser(user.getId(), user.getUsername(), user.getRole());
        return new LoginResponse(jwtService.generate(principal), "Bearer", jwtService.expirationSeconds(), toResponse(user));
    }

    public UserResponse currentUser(CurrentUser principal) {
        UserEntity user = userMapper.selectById(principal.id());
        if (user == null || !user.getUsername().equals(principal.username())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return toResponse(user);
    }

    private String normalizeNickname(String nickname, String username) {
        return nickname == null || nickname.isBlank() ? username : nickname.trim();
    }

    private boolean hasUniqueConstraintCause(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLIntegrityConstraintViolationException) return true;
        }
        return false;
    }

    private UserResponse toResponse(UserEntity user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getNickname(), user.getAvatar(), user.getRole());
    }
}
