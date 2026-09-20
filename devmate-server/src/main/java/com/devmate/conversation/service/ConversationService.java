package com.devmate.conversation.service;

import com.devmate.ai.application.AiChatRequest;
import com.devmate.ai.application.AiChatResult;
import com.devmate.ai.application.AiGateway;
import com.devmate.ai.application.AiGatewayException;
import com.devmate.ai.config.AiProperties;
import com.devmate.common.api.ErrorCode;
import com.devmate.common.api.PageResult;
import com.devmate.common.exception.BusinessException;
import com.devmate.conversation.dto.CreateConversationRequest;
import com.devmate.conversation.dto.SendMessageRequest;
import com.devmate.conversation.entity.ConversationEntity;
import com.devmate.conversation.entity.ConversationMessageEntity;
import com.devmate.conversation.mapper.ConversationMapper;
import com.devmate.conversation.mapper.ConversationMessageMapper;
import com.devmate.conversation.vo.ConversationResponse;
import com.devmate.conversation.vo.MessageResponse;
import com.devmate.conversation.vo.SendMessageResponse;
import com.devmate.project.service.ProjectService;
import com.devmate.project.vo.ProjectResponse;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {
    public static final int MAX_PAGE = 10_000;
    public static final int MAX_PAGE_SIZE = 100;
    public static final String DEFAULT_TITLE = "New conversation";
    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationService.class);

    private final ProjectService projectService;
    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final ConversationTransactionService transactionService;
    private final ProjectChatPromptBuilder promptBuilder;
    private final AiGateway aiGateway;
    private final AiProperties properties;

    public ConversationService(ProjectService projectService, ConversationMapper conversationMapper,
                               ConversationMessageMapper messageMapper,
                               ConversationTransactionService transactionService,
                               ProjectChatPromptBuilder promptBuilder, AiGateway aiGateway,
                               AiProperties properties) {
        this.projectService = projectService;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.transactionService = transactionService;
        this.promptBuilder = promptBuilder;
        this.aiGateway = aiGateway;
        this.properties = properties;
    }

    @Transactional
    public ConversationResponse create(Long ownerUserId, Long projectId, CreateConversationRequest request) {
        projectService.requireOwnedActiveProject(ownerUserId, projectId);
        Objects.requireNonNull(request, "request must not be null");
        ConversationEntity conversation = new ConversationEntity();
        conversation.setProjectId(projectId);
        conversation.setOwnerUserId(ownerUserId);
        conversation.setTitle(normalizeTitle(request.title()));
        if (conversationMapper.insertConversation(conversation) != 1 || conversation.getId() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return toResponse(requireConversation(ownerUserId, projectId, conversation.getId()));
    }

    @Transactional(readOnly = true)
    public PageResult<ConversationResponse> list(Long ownerUserId, Long projectId, int page, int pageSize) {
        projectService.requireOwnedActiveProject(ownerUserId, projectId);
        validatePagination(page, pageSize);
        long offset = Math.multiplyExact((long) page - 1L, pageSize);
        long total = conversationMapper.countOwnedActive(ownerUserId, projectId);
        List<ConversationResponse> items = conversationMapper.findOwnedActivePage(
                ownerUserId, projectId, offset, pageSize).stream().map(this::toResponse).toList();
        return new PageResult<>(page, pageSize, total, items);
    }

    @Transactional(readOnly = true)
    public ConversationResponse get(Long ownerUserId, Long projectId, Long conversationId) {
        projectService.requireOwnedActiveProject(ownerUserId, projectId);
        return toResponse(requireConversation(ownerUserId, projectId, conversationId));
    }

    @Transactional(readOnly = true)
    public PageResult<MessageResponse> listMessages(Long ownerUserId, Long projectId, Long conversationId,
                                                    int page, int pageSize) {
        projectService.requireOwnedActiveProject(ownerUserId, projectId);
        validatePagination(page, pageSize);
        requireConversation(ownerUserId, projectId, conversationId);
        long offset = Math.multiplyExact((long) page - 1L, pageSize);
        long total = messageMapper.countOwned(ownerUserId, projectId, conversationId);
        List<MessageResponse> items = messageMapper.findOwnedPage(
                ownerUserId, projectId, conversationId, offset, pageSize).stream()
                .map(this::toMessageResponse).toList();
        return new PageResult<>(page, pageSize, total, items);
    }

    public SendMessageResponse send(Long ownerUserId, Long projectId, Long conversationId,
                                    SendMessageRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String clientRequestId = normalizeUuid(request.clientRequestId());
        String content = normalizeContent(request.content());
        ProjectResponse project = projectService.get(ownerUserId, projectId);
        requireConversation(ownerUserId, projectId, conversationId);
        if (!aiGateway.enabled()) {
            throw new BusinessException(ErrorCode.AI_SERVICE_DISABLED);
        }

        GenerationStart start = transactionService.begin(
                ownerUserId, projectId, conversationId, clientRequestId, content);
        if (start.isExisting()) {
            return start.existingResponse();
        }
        GenerationContext context = start.context();
        long started = System.nanoTime();
        try {
            List<ConversationMessageEntity> history = messageMapper.findRecentSuccessful(
                    ownerUserId, projectId, conversationId, properties.getMaxContextMessages());
            AiChatRequest chatRequest = promptBuilder.build(project, history, content);
            AiChatResult result = aiGateway.chat(chatRequest);
            SendMessageResponse response = transactionService.complete(context, result);
            LOGGER.info("AI invocation completed invocationId={} provider={} model={} status={} inputTokens={} "
                            + "outputTokens={} durationMs={}", context.invocationId(), aiGateway.provider(),
                    aiGateway.model(), "SUCCEEDED", result.inputTokens(), result.outputTokens(), result.durationMs());
            return response;
        } catch (AiGatewayException exception) {
            long durationMs = elapsedMillis(started);
            transactionService.fail(context, exception.getErrorCode(), durationMs);
            LOGGER.warn("AI invocation failed invocationId={} provider={} model={} status={} errorCode={} durationMs={}",
                    context.invocationId(), aiGateway.provider(), aiGateway.model(), "FAILED",
                    exception.getErrorCode().name(), durationMs);
            throw new BusinessException(exception.getErrorCode());
        } catch (BusinessException exception) {
            transactionService.fail(context, ErrorCode.AI_PROVIDER_UNAVAILABLE, elapsedMillis(started));
            throw exception;
        } catch (RuntimeException exception) {
            long durationMs = elapsedMillis(started);
            transactionService.fail(context, ErrorCode.AI_PROVIDER_UNAVAILABLE, durationMs);
            LOGGER.warn("AI invocation failed invocationId={} provider={} model={} status={} errorCode={} durationMs={}",
                    context.invocationId(), aiGateway.provider(), aiGateway.model(), "FAILED",
                    ErrorCode.AI_PROVIDER_UNAVAILABLE.name(), durationMs);
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
    }

    private ConversationEntity requireConversation(Long ownerUserId, Long projectId, Long conversationId) {
        if (conversationId == null || conversationId < 1) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }
        ConversationEntity conversation = conversationMapper.findOwnedActive(ownerUserId, projectId, conversationId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }

    private String normalizeTitle(String title) {
        if (title == null || title.strip().isEmpty()) {
            return DEFAULT_TITLE;
        }
        String normalized = title.strip();
        if (normalized.length() > 200) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }
        return normalized;
    }

    private String normalizeContent(String content) {
        if (content == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }
        String normalized = content.strip();
        if (normalized.isEmpty()
                || normalized.codePointCount(0, normalized.length()) > properties.getMaxMessageCharacters()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }
        return normalized;
    }

    private String normalizeUuid(String value) {
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("UUID is not canonical");
            }
            return uuid.toString();
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }
    }

    private void validatePagination(int page, int pageSize) {
        if (page < 1 || page > MAX_PAGE || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }
    }

    private ConversationResponse toResponse(ConversationEntity conversation) {
        return new ConversationResponse(conversation.getId(), conversation.getProjectId(), conversation.getTitle(),
                conversation.getGenerationState(), conversation.getCreateTime().toInstant(ZoneOffset.UTC),
                conversation.getUpdateTime().toInstant(ZoneOffset.UTC));
    }

    private MessageResponse toMessageResponse(ConversationMessageEntity message) {
        return new MessageResponse(message.getId(), message.getRole(), message.getContent(),
                message.getSequenceNo(), message.getCreateTime().toInstant(ZoneOffset.UTC));
    }

    private long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }
}
