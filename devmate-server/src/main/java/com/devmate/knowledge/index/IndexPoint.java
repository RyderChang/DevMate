package com.devmate.knowledge.index;

public record IndexPoint(int ordinal, String id, String sha, Integer tokens, boolean confirmed) {}
