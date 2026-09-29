package com.devmate.knowledge.vo;

public record RetrievalHit(String pointId, double score, long documentId, String filename,
        long processingId, long indexId, long processingGeneration, long indexGeneration,
        String parserVersion, String strategyVersion, String sourceSha256, String chunkSha256,
        int ordinal, int start, int end, int startLine, int endLine, String text) {}
