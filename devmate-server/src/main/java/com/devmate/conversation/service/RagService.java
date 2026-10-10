package com.devmate.conversation.service;

import com.devmate.ai.application.*;
import com.devmate.ai.config.AiProperties;
import com.devmate.common.api.ErrorCode;
import com.devmate.common.exception.BusinessException;
import com.devmate.conversation.dto.SendMessageRequest;
import com.devmate.conversation.mapper.ConversationMessageMapper;
import com.devmate.conversation.vo.RagMessageResponse;
import com.devmate.knowledge.application.RetrievalService;
import com.devmate.knowledge.dto.RetrievalRequest;
import com.devmate.project.service.ProjectService;
import java.time.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class RagService {
    private static final Logger LOG=LoggerFactory.getLogger(RagService.class);
    private final RagTransactions transactions;
    private final RetrievalService retrieval;
    private final ProjectService projects;
    private final ConversationService conversations;
    private final ConversationMessageMapper messages;
    private final ProjectRagPromptBuilder prompts;
    private final RagOutputValidator outputs;
    private final RagChatCalls calls;
    private final AiGateway gateway;
    private final RagProperties properties;
    private final AiProperties ai;
    private final Clock clock;
    public RagService(RagTransactions transactions, RetrievalService retrieval, ProjectService projects,
                      ConversationService conversations, ConversationMessageMapper messages, ProjectRagPromptBuilder prompts,
                      RagOutputValidator outputs, RagChatCalls calls, AiGateway gateway, RagProperties properties,
                      AiProperties ai, @Qualifier("conversationClock") Clock clock) {
        this.transactions=transactions; this.retrieval=retrieval; this.projects=projects; this.conversations=conversations;
        this.messages=messages; this.prompts=prompts; this.outputs=outputs; this.calls=calls; this.gateway=gateway;
        this.properties=properties; this.ai=ai; this.clock=clock;
    }
    public RagMessageResponse send(long owner,long project,long conversation,SendMessageRequest request) {
        String uuid=RagInput.uuid(request.clientRequestId()), content=RagInput.content(request.content());
        com.devmate.project.vo.ProjectResponse projectInfo;
        RagTransactions.Start start;
        try {
            projectInfo=projects.get(owner,project);
            conversations.get(owner,project,conversation);
            if(!properties.isEnabled())throw new BusinessException(ErrorCode.RAG_DISABLED);
            if(!gateway.enabled())throw new BusinessException(ErrorCode.AI_SERVICE_DISABLED);
            start=transactions.begin(owner,project,conversation,uuid,content);
        }
        catch(org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException error) {
            throw new BusinessException(ErrorCode.RAG_DATABASE_UNAVAILABLE);
        }
        if(start.error()!=null)throw new BusinessException(start.error());
        if(start.existing()!=null)return start.existing();
        var context=start.context();
        try(var budget=CallBudget.open(remaining(context))) {
            var found=retrieval.search(owner,project,new RetrievalRequest(content,5));
            if(found.incomplete())throw new BusinessException(ErrorCode.RAG_RETRIEVAL_INCOMPLETE);
            if(found.hits().isEmpty())throw new BusinessException(ErrorCode.RAG_CONTEXT_UNAVAILABLE);
            var history=messages.findRecentSuccessful(owner,project,conversation,ai.getMaxContextMessages());
            var provided=prompts.build(projectInfo,history,content,found.hits());
            AiChatResult result=calls.call(remaining(context),() -> {
                transactions.dispatch(context,found,provided);
                remaining(context);
                var response=gateway.chat(provided.request());
                try { transactions.receipt(context,response); }
                catch(RuntimeException receiptError) {
                    try { transactions.retainUsage(context,response); }
                    catch(RuntimeException usageError) { LOG.warn("RAG usage unconfirmed traceId={} invocationId={}",MDC.get("traceId"),context.invocation()); }
                    throw receiptError;
                }
                return response;
            });
            var answer=outputs.validate(result.content(),provided.sources().keySet());
            return transactions.complete(context,provided,answer,result);
        } catch(RuntimeException error) {
            ErrorCode code=error instanceof AiGatewayException failure?failure.getErrorCode():
                    error instanceof BusinessException failure?stored(failure):
                    error instanceof org.springframework.dao.DataAccessException || error instanceof org.springframework.transaction.TransactionException
                            ?ErrorCode.RAG_DATABASE_UNAVAILABLE:ErrorCode.AI_PROVIDER_UNAVAILABLE;
            RagFailureDiagnostic diagnostic = code != ErrorCode.AI_RESPONSE_INVALID ? null
                    : error instanceof AiGatewayException failure ? RagFailureDiagnostic.gateway(failure.getResponseIssue())
                    : error instanceof RagOutputException failure ? RagFailureDiagnostic.output(failure.issue()) : null;
            try { transactions.fail(context,code,diagnostic); }
            catch(RuntimeException stateError) { LOG.warn("RAG state unconfirmed traceId={} invocationId={} code=STATE_UNCONFIRMED",MDC.get("traceId"),context.invocation()); }
            throw new BusinessException(code);
        }
    }
    private ErrorCode stored(BusinessException error) {
        return error.getErrorCode() == null ? ErrorCode.AI_PROVIDER_UNAVAILABLE : error.getErrorCode();
    }
    private Duration remaining(RagTransactions.Context context) {
        Duration result=Duration.between(clock.instant(),context.deadline().toInstant(ZoneOffset.UTC));
        if(result.isZero() || result.isNegative())throw new BusinessException(ErrorCode.AI_REQUEST_EXPIRED);
        return CallBudget.cap(result);
    }
}
