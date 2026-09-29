package com.devmate.knowledge.dto;

import jakarta.validation.constraints.*;

public record RetrievalRequest(@NotBlank @Size(max=8000) String query, @Min(1) @Max(20) Integer topK) {}
