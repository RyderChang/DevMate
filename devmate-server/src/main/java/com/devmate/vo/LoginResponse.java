package com.devmate.vo;

public record LoginResponse(String token, String tokenType, long expiresIn, UserResponse user) {
}
