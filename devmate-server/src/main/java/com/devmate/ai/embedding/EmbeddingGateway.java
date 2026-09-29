package com.devmate.ai.embedding;

import java.util.List;

/** Exact counts and inference share one frozen local specification. No chat or provider DTOs. */
public interface EmbeddingGateway {
    List<Integer> count(List<String> documents);
    List<float[]> embed(String operation, List<String> documents, List<Integer> expectedCounts);
    /** True only with a durable response proving the specific operation ended. Absence is not proof. */
    boolean ended(String operation);
}
