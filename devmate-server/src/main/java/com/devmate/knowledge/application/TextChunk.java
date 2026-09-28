package com.devmate.knowledge.application;

/** Offsets refer to normalized Unicode code points, with a half-open interval. */
public record TextChunk(int ordinal, int start, int end, int startLine, int endLine,
                        String text, String sha256, int byteSize) {}
