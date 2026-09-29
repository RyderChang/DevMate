package com.devmate.conversation.vo;

import java.util.List;

public record RagMessageResponse(long conversationId, MessageResponse userMessage, MessageResponse assistantMessage,
                                 InvocationSummary invocation, RagSummary rag, List<CitationResponse> citations) {}
