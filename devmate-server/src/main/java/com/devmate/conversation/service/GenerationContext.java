package com.devmate.conversation.service;

import com.devmate.conversation.entity.ConversationMessageEntity;
import java.time.LocalDateTime;

record GenerationContext(
        Long ownerUserId,
        Long projectId,
        Long conversationId,
        Long invocationId,
        ConversationMessageEntity userMessage,
        LocalDateTime leaseStartedAt) {
}
