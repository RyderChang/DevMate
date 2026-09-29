package com.devmate.conversation.service;

import com.devmate.conversation.mapper.RagJournal;
import java.time.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Expiry only fences publication; it is never evidence that a remote model did not execute. */
@Component
public class RagRecovery {
    private static final Logger LOG=LoggerFactory.getLogger(RagRecovery.class);
    private final RagJournal journal;
    private final RagTransactions transactions;
    private final RagProperties properties;
    private final Clock clock;
    public RagRecovery(RagJournal journal,RagTransactions transactions,RagProperties properties,@Qualifier("conversationClock") Clock clock) {
        this.journal=journal; this.transactions=transactions; this.properties=properties; this.clock=clock;
    }
    @Scheduled(fixedDelay=60000)
    public void runOnce() {
        if(!properties.isSchedulingEnabled())return;
        try {
            for(long id:journal.expired(LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC))) {
                try { transactions.expire(id); }
                catch(RuntimeException error) { LOG.warn("RAG expiry unconfirmed traceId={} invocationId={}",MDC.get("traceId"),id); }
            }
        } catch(RuntimeException error) { LOG.warn("RAG recovery unavailable traceId={}",MDC.get("traceId")); }
    }
}
