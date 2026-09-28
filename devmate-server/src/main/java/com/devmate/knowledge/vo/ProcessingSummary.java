package com.devmate.knowledge.vo;

public record ProcessingSummary(long processingId, long generation, String state, String sourceSha256,
                                String normalizedSha256, String parserVersion, String strategyVersion,
                                int chunkCount, long textBytes, String errorCode) {}
