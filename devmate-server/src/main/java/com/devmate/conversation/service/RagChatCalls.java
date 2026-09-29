package com.devmate.conversation.service;

import com.devmate.ai.application.*;
import com.devmate.common.api.ErrorCode;
import com.devmate.common.exception.BusinessException;
import java.time.Duration;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

/** Four non-queued chat calls. A timed-out worker retains its slot until it really exits. */
@Component
public class RagChatCalls {
    private final Semaphore slots = new Semaphore(4);
    public AiChatResult call(Duration remaining, Callable<AiChatResult> action) {
        Duration timeout = remaining.compareTo(Duration.ofSeconds(120)) < 0 ? remaining : Duration.ofSeconds(120);
        if(timeout.isNegative() || timeout.isZero())throw new BusinessException(ErrorCode.AI_REQUEST_EXPIRED);
        if(!slots.tryAcquire())throw new BusinessException(ErrorCode.AI_REQUEST_IN_PROGRESS);
        var task=new FutureTask<AiChatResult>(() -> {
            try(var budget=CallBudget.open(timeout)) { return action.call(); }
        });
        var trace = org.slf4j.MDC.getCopyOfContextMap();
        Thread.ofVirtual().name("rag-chat").start(() -> {
            if (trace != null) org.slf4j.MDC.setContextMap(trace);
            try { task.run(); } finally { org.slf4j.MDC.clear(); slots.release(); }
        });
        try { return task.get(timeout.toNanos(),TimeUnit.NANOSECONDS); }
        catch(TimeoutException error) { task.cancel(true); throw new BusinessException(ErrorCode.AI_PROVIDER_TIMEOUT); }
        catch(InterruptedException error) { task.cancel(true); Thread.currentThread().interrupt(); throw new BusinessException(ErrorCode.AI_PROVIDER_TIMEOUT); }
        catch(ExecutionException error) {
            if(error.getCause() instanceof RuntimeException failure)throw failure;
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
    }
}
