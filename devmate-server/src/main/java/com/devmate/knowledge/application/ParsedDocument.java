package com.devmate.knowledge.application;

import java.util.List;

public record ParsedDocument(String normalizedSha256, List<TextChunk> chunks, long textBytes) {
    public ParsedDocument { chunks = List.copyOf(chunks); }
}
