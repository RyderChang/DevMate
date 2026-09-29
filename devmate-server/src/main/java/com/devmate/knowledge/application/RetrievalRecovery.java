package com.devmate.knowledge.application;

import com.devmate.ai.embedding.EmbeddingGateway;
import com.devmate.knowledge.config.RetrievalProperties;
import com.devmate.knowledge.infrastructure.RetrievalJournal;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RetrievalRecovery {
    private static final Logger LOG=LoggerFactory.getLogger(RetrievalRecovery.class);
    private final RetrievalJournal journal;
    private final RetrievalTransactions transactions;
    private final EmbeddingGateway model;
    private final RetrievalProperties properties;
    private final Clock clock;
    private final Semaphore slot=new Semaphore(1);
    public RetrievalRecovery(RetrievalJournal journal,RetrievalTransactions transactions,EmbeddingGateway model,RetrievalProperties properties,@Qualifier("knowledgeClock") Clock clock){this.journal=journal;this.transactions=transactions;this.model=model;this.properties=properties;this.clock=clock;}
    @Scheduled(fixedDelay=60000,initialDelay=60000) public void scheduled(){if(properties.isSchedulingEnabled())runOnce();}
    public void runOnce(){
        if(!slot.tryAcquire())return;
        try(var trace=MDC.putCloseable("traceId",UUID.randomUUID().toString())){
            var now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
            for(var op:journal.due(now))transactions.recover(op.id(),op.state().equals("MODEL_ENDED") || model.ended(op.id()));
            for(var id:journal.expired(now))transactions.expire(id);
        }catch(RuntimeException error){LOG.warn("Retrieval recovery deferred traceId={} code=STATE_UNCONFIRMED",MDC.get("traceId"));}
        finally{slot.release();}
    }
}
