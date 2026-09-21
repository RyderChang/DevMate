package com.devmate.conversation.vo;

public record SendMessageResponse(
        Long conversationId,
        MessageResponse userMessage,
        MessageResponse assistantMessage,
        InvocationSummary invocation) {
}
