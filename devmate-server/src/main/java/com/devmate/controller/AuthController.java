package com.devmate.controller;

import com.devmate.common.api.Result;
import com.devmate.dto.LoginRequest;
import com.devmate.dto.RegisterRequest;
import com.devmate.security.CurrentUser;
import com.devmate.service.UserService;
import com.devmate.vo.LoginResponse;
import com.devmate.vo.UserResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public Result<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.success(userService.register(request));
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(userService.login(request));
    }

    @GetMapping("/me")
    public Result<UserResponse> me(@AuthenticationPrincipal CurrentUser principal) {
        return Result.success(userService.currentUser(principal));
    }
}
