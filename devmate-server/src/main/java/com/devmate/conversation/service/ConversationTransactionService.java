package com.devmate.conversation.service;

import com.devmate.ai.application.AiChatResult;
import com.devmate.ai.application.AiGateway;
import com.devmate.ai.config.AiProperties;
import com.devmate.common.api.ErrorCode;
import com.devmate.common.exception.BusinessException;
import com.devmate.conversation.entity.AiInvocationEntity;
import com.devmate.conversation.entity.ConversationEntity;
import com.devmate.conversation.entity.ConversationMessageEntity;
import com.devmate.conversation.mapper.AiInvocationMapper;
import com.devmate.conversation.mapper.ConversationMapper;
import com.devmate.conversation.mapper.ConversationMessageMapper;
import com.devmate.conversation.vo.InvocationSummary;
import com.devmate.conversation.vo.MessageResponse;
import com.devmate.conversation.vo.SendMessageResponse;
import com.devmate.project.service.ProjectService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationTransactionService {
    private final ProjectService projectService;
    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final AiInvocationMapper invocationMapper;
    private final AiGateway aiGateway;
    private final AiProperties properties;
    private final Clock clock;

    public ConversationTransactionService(ProjectService projectService, ConversationMapper conversationMapper,
                                          ConversationMessageMapper messageMapper,
                                          AiInvocationMapper invocationMapper, AiGateway aiGateway,
                                          AiProperties properties, Clock clock) {
        this.projectService = projectService;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.invocationMapper = invocationMapper;
        this.aiGateway = aiGateway;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public GenerationStart begin(Long ownerUserId, Long projectId, Long conversationId,
                                 String clientRequestId, String content) {
        projectService.requireOwnedActiveProject(ownerUserId, projectId);
        ConversationEntity conversation = lockConversation(ownerUserId, projectId, conversationId);
        AiInvocationEntity existing = invocationMapper.findOwnedByRequest(
                ownerUserId, projectId, conversationId, clientRequestId);
        if (existing != null) {
            return handleExisting(ownerUserId, projectId, conversationId, existing);
        }

        LocalDateTime now = now();
        if ("GENERATING".equals(conversation.getGenerationState())) {
            LocalDateTime expiry = now.minus(properties.getGenerationLease());
            if (conversation.getGenerationStartedAt() != null
                    && conversation.getGenerationStartedAt().isAfter(expiry)) {
                throw new BusinessException(ErrorCode.AI_REQUEST_IN_PROGRESS);
            }
            invocationMapper.failPendingForConversation(conversationId,
                    ErrorCode.AI_REQUEST_EXPIRED.name(), now);
            if (conversationMapper.releaseGeneration(ownerUserId, projectId, conversationId,
                    conversation.getGenerationStartedAt()) != 1) {
                throw new BusinessException(ErrorCode.AI_REQUEST_IN_PROGRESS);
            }
        }

        LocalDateTime leaseStartedAt = now();
        if (conversationMapper.startGeneration(ownerUserId, projectId, conversationId, leaseStartedAt) != 1) {
            throw new BusinessException(ErrorCode.AI_REQUEST_IN_PROGRESS);
        }
        ConversationMessageEntity userMessage = new ConversationMessageEntity();
        userMessage.setConversationId(conversationId);
        userMessage.setSequenceNo(messageMapper.nextSequence(conversationId));
        userMessage.setRole("USER");
        userMessage.setContent(content);
        userMessage.setCreateTime(now);
        if (messageMapper.insertMessage(userMessage) != 1 || userMessage.getId() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }

        AiInvocationEntity invocation = new AiInvocationEntity();
        invocation.setConversationId(conversationId);
        invocation.setClientRequestId(clientRequestId);
        invocation.setUserMessageId(userMessage.getId());
        invocation.setProvider(aiGateway.provider());
        invocation.setModel(aiGateway.model());
        invocation.setPromptTemplateVersion(ProjectChatPromptBuilder.TEMPLATE_VERSION);
        invocation.setStatus("PENDING");
        invocation.setStartedAt(now);
        if (invocationMapper.insertInvocation(invocation) != 1 || invocation.getId() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return GenerationStart.created(new GenerationContext(ownerUserId, projectId, conversationId,
                invocation.getId(), userMessage, leaseStartedAt));
    }

    @Transactional
    public SendMessageResponse complete(GenerationContext context, AiChatResult result) {
        ConversationEntity conversation = lockInternalConversation(
                context.ownerUserId(), context.projectId(), context.conversationId());
        if (!ownsLease(conversation, context.leaseStartedAt())) {
            throw new BusinessException(ErrorCode.AI_REQUEST_EXPIRED);
        }
        LocalDateTime completedAt = now();
        ConversationMessageEntity assistant = new ConversationMessageEntity();
        assistant.setConversationId(context.conversationId());
        assistant.setSequenceNo(messageMapper.nextSequence(context.conversationId()));
        assistant.setRole("ASSISTANT");
        assistant.setContent(result.content());
        assistant.setCreateTime(completedAt);
        if (messageMapper.insertMessage(assistant) != 1 || assistant.getId() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        if (invocationMapper.succeed(context.invocationId(), assistant.getId(), result.providerRequestId(),
                result.inputTokens(), result.outputTokens(), result.totalTokens(), result.durationMs(), completedAt) != 1
                || conversationMapper.completeGeneration(context.ownerUserId(), context.projectId(),
                context.conversationId(), context.leaseStartedAt(), completedAt) != 1) {
            throw new BusinessException(ErrorCode.AI_REQUEST_EXPIRED);
        }
        return response(context.conversationId(), context.userMessage(), assistant,
                aiGateway.provider(), aiGateway.model(), result.inputTokens(), result.outputTokens(),
                result.totalTokens(), result.durationMs(), completedAt);
    }

    @Transactional
    public void fail(GenerationContext context, ErrorCode errorCode, long durationMs) {
        ConversationEntity conversation = lockInternalConversation(
                context.ownerUserId(), context.projectId(), context.conversationId());
        if (!ownsLease(conversation, context.leaseStartedAt())) {
            return;
        }
        LocalDateTime completedAt = now();
        if (invocationMapper.fail(context.invocationId(), errorCode.name(), durationMs, completedAt) == 1) {
            conversationMapper.releaseGeneration(context.ownerUserId(), context.projectId(),
                    context.conversationId(), context.leaseStartedAt());
        }
    }

    private GenerationStart handleExisting(Long ownerUserId, Long projectId, Long conversationId,
                                           AiInvocationEntity invocation) {
        if ("PENDING".equals(invocation.getStatus())) {
            throw new BusinessException(ErrorCode.AI_REQUEST_IN_PROGRESS);
        }
        if ("FAILED".equals(invocation.getStatus())) {
            throw new BusinessException(storedError(invocation.getErrorCode()));
        }
        ConversationMessageEntity user = messageMapper.findOwnedById(
                ownerUserId, projectId, conversationId, invocation.getUserMessageId());
        ConversationMessageEntity assistant = messageMapper.findOwnedById(
                ownerUserId, projectId, conversationId, invocation.getAssistantMessageId());
        if (user == null || assistant == null || invocation.getCompletedAt() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return GenerationStart.existing(response(conversationId, user, assistant,
                invocation.getProvider(), invocation.getModel(), invocation.getInputTokens(),
                invocation.getOutputTokens(), invocation.getTotalTokens(), invocation.getDurationMs(),
                invocation.getCompletedAt()));
    }

    private ConversationEntity lockConversation(Long ownerUserId, Long projectId, Long conversationId) {
        ConversationEntity conversation = conversationMapper.lockOwnedActive(ownerUserId, projectId, conversationId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }

    private ConversationEntity lockInternalConversation(Long ownerUserId, Long projectId, Long conversationId) {
        ConversationEntity conversation = conversationMapper.lockOwned(ownerUserId, projectId, conversationId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }

    private boolean ownsLease(ConversationEntity conversation, LocalDateTime leaseStartedAt) {
        return "GENERATING".equals(conversation.getGenerationState())
                && leaseStartedAt.equals(conversation.getGenerationStartedAt());
    }

    private ErrorCode storedError(String name) {
        try {
            ErrorCode code = ErrorCode.valueOf(name);
            return switch (code) {
                case AI_SERVICE_DISABLED, AI_REQUEST_EXPIRED, AI_PROVIDER_RATE_LIMITED,
                        AI_PROVIDER_TIMEOUT, AI_PROVIDER_UNAVAILABLE, AI_RESPONSE_INVALID -> code;
                default -> ErrorCode.AI_PROVIDER_UNAVAILABLE;
            };
        } catch (IllegalArgumentException | NullPointerException exception) {
            return ErrorCode.AI_PROVIDER_UNAVAILABLE;
        }
    }

    private SendMessageResponse response(Long conversationId, ConversationMessageEntity user,
                                         ConversationMessageEntity assistant, String provider, String model,
                                         Integer inputTokens, Integer outputTokens, Integer totalTokens,
                                         Long durationMs, LocalDateTime completedAt) {
        return new SendMessageResponse(conversationId, message(user), message(assistant),
                new InvocationSummary("SUCCEEDED", provider, model, inputTokens, outputTokens,
                        totalTokens, durationMs, instant(completedAt)));
    }

    private MessageResponse message(ConversationMessageEntity message) {
        return new MessageResponse(message.getId(), message.getRole(), message.getContent(),
                message.getSequenceNo(), instant(message.getCreateTime()));
    }

    private Instant instant(LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
