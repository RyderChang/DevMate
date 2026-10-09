package com.devmate.conversation.service;

import com.devmate.ai.application.AiChatResult;
import com.devmate.ai.application.AiGateway;
import com.devmate.ai.config.AiProperties;
import com.devmate.common.api.ErrorCode;
import com.devmate.common.exception.BusinessException;
import com.devmate.conversation.entity.*;
import com.devmate.conversation.mapper.*;
import com.devmate.conversation.vo.*;
import com.devmate.knowledge.application.RagSourceEligibility;
import com.devmate.knowledge.vo.RetrievalResponse;
import com.devmate.project.service.ProjectService;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RagTransactions {
    private final ProjectService projects;
    private final ConversationMapper conversations;
    private final ConversationMessageMapper messages;
    private final AiInvocationMapper invocations;
    private final RagJournal journal;
    private final RagSourceEligibility sources;
    private final AiGateway gateway;
    private final AiProperties ai;
    private final Clock clock;
    public RagTransactions(ProjectService projects, ConversationMapper conversations, ConversationMessageMapper messages,
                           AiInvocationMapper invocations, RagJournal journal, RagSourceEligibility sources,
                           AiGateway gateway, AiProperties ai, @Qualifier("conversationClock") Clock clock) {
        this.projects=projects; this.conversations=conversations; this.messages=messages; this.invocations=invocations;
        this.journal=journal; this.sources=sources; this.gateway=gateway; this.ai=ai; this.clock=clock;
    }
    public record Context(long owner, long project, long conversation, long invocation,
                          ConversationMessageEntity user, LocalDateTime started, LocalDateTime deadline) {}
    public record Start(Context context, RagMessageResponse existing, ErrorCode error) {}
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS); }

    @Transactional(timeout=10)
    public Start begin(long owner, long project, long conversation, String uuid, String content) {
        projects.lockOwnedActiveProject(owner,project);
        var c=conversations.lockOwnedActive(owner,project,conversation);
        if(c==null)throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        var existing=invocations.findOwnedByRequest(owner,project,conversation,uuid);
        if(existing!=null) {
            if(!"RAG".equals(existing.getMode()) || !RagInput.sha(content).equals(existing.getRequestSha256()))
                throw new BusinessException(ErrorCode.RAG_REQUEST_CONFLICT);
            if("PENDING".equals(existing.getStatus())) {
                if(!journal.deadline(existing.getId()).isAfter(now())) {
                    failLocked(c,existing.getId(),existing.getStartedAt(),ErrorCode.AI_REQUEST_EXPIRED);
                    return new Start(null,null,ErrorCode.AI_REQUEST_EXPIRED);
                }
                return new Start(null,null,ErrorCode.AI_REQUEST_IN_PROGRESS);
            }
            if("FAILED".equals(existing.getStatus()))return new Start(null,null,stored(existing.getErrorCode()));
            return new Start(null,response(owner,project,conversation,existing),null);
        }
        if("GENERATING".equals(c.getGenerationState())) {
            LocalDateTime deadline=invocations.pendingDeadline(conversation,c.getGenerationStartedAt());
            if(deadline==null)deadline=c.getGenerationStartedAt().plus(ai.getGenerationLease());
            if(deadline.isAfter(now()))throw new BusinessException(ErrorCode.AI_REQUEST_IN_PROGRESS);
            invocations.failPendingForConversation(conversation,ErrorCode.AI_REQUEST_EXPIRED.name(),now());
            conversations.releaseGeneration(owner,project,conversation,c.getGenerationStartedAt());
        }
        if(!journal.reserve(project,conversation))throw new BusinessException(ErrorCode.RAG_RECORD_LIMIT);
        LocalDateTime start=now();
        if(conversations.startGeneration(owner,project,conversation,start)!=1)throw new BusinessException(ErrorCode.AI_REQUEST_IN_PROGRESS);
        var user=new ConversationMessageEntity(); user.setConversationId(conversation); user.setSequenceNo(messages.nextSequence(conversation));
        user.setRole("USER"); user.setContent(content); user.setCreateTime(start);
        if(messages.insertMessage(user)!=1 || user.getId()==null)throw new IllegalStateException("RAG request cannot be persisted");
        var invocation=new AiInvocationEntity(); invocation.setConversationId(conversation); invocation.setClientRequestId(uuid);
        invocation.setUserMessageId(user.getId()); invocation.setProvider(gateway.provider()); invocation.setModel(gateway.model());
        invocation.setPromptTemplateVersion(ProjectRagPromptBuilder.TEMPLATE_VERSION); invocation.setStatus("PENDING"); invocation.setStartedAt(start);
        invocation.setMode("RAG"); invocation.setRequestSha256(RagInput.sha(content)); invocation.setLeaseExpiresAt(start.plusMinutes(12));
        if(invocations.insertInvocation(invocation)!=1 || invocation.getId()==null)throw new IllegalStateException("RAG invocation cannot be persisted");
        journal.begin(invocation.getId(),start.plusMinutes(11));
        return new Start(new Context(owner,project,conversation,invocation.getId(),user,start,start.plusMinutes(11)),null,null);
    }

    @Transactional(timeout=10)
    public void dispatch(Context context, RetrievalResponse retrieval, ProjectRagPromptBuilder.Provided provided) {
        requireLive(context);
        if(!sources.validate(context.owner(),context.project(),List.copyOf(provided.sources().values())))
            throw new BusinessException(ErrorCode.RAG_SOURCE_CHANGED);
        requireLive(context);
        journal.dispatched(context.invocation(),retrieval,now());
    }

    /** Receipt commits independently before parsing or publication, and may arrive after lease expiry. */
    @Transactional(timeout=10)
    public void receipt(Context context, AiChatResult result) {
        projects.lockProjectForMaintenance(context.owner(),context.project());
        conversations.lockOwned(context.owner(),context.project(),context.conversation());
        journal.receipt(context.invocation(),result);
    }

    /** A rejected detail update must not discard known usage when the independent invocation row is writable. */
    @Transactional(timeout=10)
    public void retainUsage(Context context, AiChatResult result) {
        projects.lockProjectForMaintenance(context.owner(),context.project());
        conversations.lockOwned(context.owner(),context.project(),context.conversation());
        journal.usage(context.invocation(),result);
        journal.unknown(context.invocation());
    }

    @Transactional(timeout=10)
    public RagMessageResponse complete(Context context, ProjectRagPromptBuilder.Provided provided,
                                       RagOutputValidator.Answer answer, AiChatResult result) {
        requireLive(context);
        if(!sources.validate(context.owner(),context.project(),List.copyOf(provided.sources().values())))
            throw new BusinessException(ErrorCode.RAG_SOURCE_CHANGED);
        // All supplied evidence must remain eligible, including chunks the model did not cite.
        var assistant=new ConversationMessageEntity(); assistant.setConversationId(context.conversation());
        assistant.setSequenceNo(messages.nextSequence(context.conversation())); assistant.setRole("ASSISTANT");
        assistant.setContent(answer.text()); assistant.setCreateTime(now());
        if(messages.insertMessage(assistant)!=1 || assistant.getId()==null)throw new IllegalStateException("Answer cannot be saved");
        for(String id:answer.citationIds())journal.save(context.invocation(),id,CitationSource.from(provided.sources().get(id)));
        requireLive(context);
        journal.checked(context.invocation(),now());
        if(invocations.succeed(context.invocation(),assistant.getId(),result.providerRequestId(),result.inputTokens(),
                result.outputTokens(),result.totalTokens(),result.durationMs(),now())!=1
                || conversations.completeGeneration(context.owner(),context.project(),context.conversation(),context.started(),now())!=1)
            throw new BusinessException(ErrorCode.AI_REQUEST_EXPIRED);
        return response(context.owner(),context.project(),context.conversation(),invocations.selectById(context.invocation()));
    }

    @Transactional(timeout=10)
    public void fail(Context context, ErrorCode error, RagFailureDiagnostic diagnostic) {
        projects.lockProjectForMaintenance(context.owner(),context.project());
        var c=conversations.lockOwned(context.owner(),context.project(),context.conversation());
        failLocked(c,context.invocation(),context.started(),error,diagnostic);
    }
    @Transactional(timeout=10)
    public void expire(long invocation) {
        var owner=journal.owner(invocation);
        projects.lockProjectForMaintenance(owner.owner(),owner.project());
        var c=conversations.lockOwned(owner.owner(),owner.project(),owner.conversation());
        var i=invocations.selectById(invocation);
        if("PENDING".equals(i.getStatus()) && !journal.deadline(invocation).isAfter(now()))
            failLocked(c,invocation,i.getStartedAt(),ErrorCode.AI_REQUEST_EXPIRED);
    }
    private void failLocked(ConversationEntity c,long invocation,LocalDateTime start,ErrorCode error) {
        failLocked(c,invocation,start,error,null);
    }
    private void failLocked(ConversationEntity c,long invocation,LocalDateTime start,ErrorCode error,RagFailureDiagnostic diagnostic) {
        journal.unknown(invocation);
        if(invocations.fail(invocation,error.name(),Math.max(0,Duration.between(start,now()).toMillis()),now())==1) {
            if(diagnostic!=null)journal.failure(invocation,diagnostic.stage(),diagnostic.category());
            if(c!=null && start.equals(c.getGenerationStartedAt()))
                conversations.releaseGeneration(c.getOwnerUserId(),c.getProjectId(),c.getId(),start);
        }
    }
    private void requireLive(Context context) {
        projects.lockOwnedActiveProject(context.owner(),context.project());
        var c=conversations.lockOwnedActive(context.owner(),context.project(),context.conversation());
        var i=invocations.selectById(context.invocation());
        if(c==null || !context.started().equals(c.getGenerationStartedAt()) || !"GENERATING".equals(c.getGenerationState())
                || !"PENDING".equals(i.getStatus()) || !context.deadline().isAfter(now()) || !i.getLeaseExpiresAt().isAfter(now()))
            throw new BusinessException(ErrorCode.AI_REQUEST_EXPIRED);
    }
    private ErrorCode stored(String name) {
        try { return ErrorCode.valueOf(name); } catch(RuntimeException error) { return ErrorCode.AI_PROVIDER_UNAVAILABLE; }
    }
    private MessageResponse message(ConversationMessageEntity m) { return new MessageResponse(m.getId(),m.getRole(),m.getContent(),m.getSequenceNo(),m.getCreateTime().toInstant(ZoneOffset.UTC)); }
    private RagMessageResponse response(long owner,long project,long conversation,AiInvocationEntity i) {
        var saved=journal.citations(i.getId());
        var available=sources.available(owner,project,saved.stream().map(s->s.source().location()).toList());
        return new RagMessageResponse(conversation,
                message(messages.findOwnedById(owner,project,conversation,i.getUserMessageId())),
                message(messages.findOwnedById(owner,project,conversation,i.getAssistantMessageId())),
                new InvocationSummary(i.getStatus(),i.getProvider(),i.getModel(),i.getInputTokens(),i.getOutputTokens(),i.getTotalTokens(),i.getDurationMs(),i.getCompletedAt().toInstant(ZoneOffset.UTC)),
                journal.summary(i.getId()),saved.stream().map(s->new CitationResponse(s.id(),s.source(),available.contains(s.source().pointId()))).toList());
    }
}
