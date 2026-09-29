package com.devmate.knowledge.retrieval;

/** Validated vector metadata. It cannot supply a document body. */
public record VectorCandidate(String pointId, double score, String sourceKey, int ordinal,
        String chunkSha, String sourceSha) {}
