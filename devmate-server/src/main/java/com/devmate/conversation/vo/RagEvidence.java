package com.devmate.conversation.vo;

import java.util.List;

/** Persisted answer provenance with current source availability, never source text. */
public record RagEvidence(RagSummary rag, List<CitationResponse> citations) {}
