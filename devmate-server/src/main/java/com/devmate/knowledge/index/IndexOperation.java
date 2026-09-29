package com.devmate.knowledge.index;

public record IndexOperation(String id, long indexId, String kind, int first, int count, String manifest, int tokens, String state, long version) {}
