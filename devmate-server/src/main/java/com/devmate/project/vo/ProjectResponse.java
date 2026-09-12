package com.devmate.project.vo;

import java.time.Instant;

public record ProjectResponse(
        Long id,
        String name,
        String description,
        Instant createTime,
        Instant updateTime) {
}
