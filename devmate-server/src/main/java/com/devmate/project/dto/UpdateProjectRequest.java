package com.devmate.project.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record UpdateProjectRequest(
        @NotNull @Schema(minLength = 1, maxLength = 100) String name,
        @Schema(maxLength = 1000) String description) {
}
