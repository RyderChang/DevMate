package com.devmate.security;

import java.util.List;

public record CurrentUser(Long id, String username, List<String> roles) {
    public CurrentUser {
        roles = roles == null ? List.of() : roles.stream().distinct().sorted().toList();
    }
}
