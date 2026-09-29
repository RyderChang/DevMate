package com.devmate.knowledge.application;

import com.devmate.ai.embedding.*;
import com.devmate.common.exception.BusinessException;
import com.devmate.knowledge.config.IndexProperties;
import com.devmate.knowledge.infrastructure.IndexJournal;
import com.devmate.knowledge.index.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** One step per claimed work, no in-memory queue. Every restart recovers from the durable journal. */
@Component
public class IndexRecovery {
    private static final Logger LOG=LoggerFactory.getLogger(IndexRecovery.class);
    private final IndexTransactions transactions;
    private final IndexJournal journal;
    private final EmbeddingGateway model;
    private final VectorStore vectors;
    private final IndexProperties properties;
    private final Clock clock;
    private final Semaphore slot=new Semaphore(1);
    public IndexRecovery(IndexTransactions transactions,IndexJournal journal,EmbeddingGateway model,VectorStore vectors,IndexProperties properties,@Qualifier("knowledgeClock") Clock clock){
        this.transactions=transactions;this.journal=journal;this.model=model;this.vectors=vectors;this.properties=properties;this.clock=clock;
    }
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    @Scheduled(fixedDelay=60000,initialDelay=60000) public void scheduled(){if(properties.isSchedulingEnabled())runOnce();}
    public int runOnce(){
        if(!slot.tryAcquire())return 0;
        try(var trace=MDC.putCloseable("traceId",UUID.randomUUID().toString())){
            transactions.maintain();int steps=0;
            for(long id:journal.cleanup(now()))cleanup(id);
            if(transactions.enabled())for(long id:journal.due(now()))if(step(id))steps++;
            return steps;
        }catch(RuntimeException failure){LOG.warn("Index recovery deferred traceId={} code=STATE_UNCONFIRMED",MDC.get("traceId"));return 0;}
        finally{slot.release();}
    }
    public boolean run(long id){if(!slot.tryAcquire())return false;try(var trace=MDC.putCloseable("traceId",UUID.randomUUID().toString())){return step(id);}finally{slot.release();}}
    private boolean step(long id){
        IndexWork claim=transactions.claim(id);if(claim==null)return false;IndexOperation operation=null;
        try{
            if(!journal.counted(id,claim.chunks())){
                int offset=0;while(offset<claim.chunks() && journal.points(id,offset,1).getFirst().tokens()!=null)offset++;
                var texts=transactions.read(claim,offset,Math.min(4,claim.chunks()-offset));if(texts.isEmpty())return true;
                EmbeddingSpec.inputs(texts.stream().map(TextChunk::text).toList());
                var counts=model.count(texts.stream().map(TextChunk::text).toList());
                if(counts.size()!=texts.size())throw new EmbeddingFailure("INVALID_COUNTS",true);
                transactions.counted(claim,offset,counts);
                if(journal.expectedTokens(id)>1000000)throw new EmbeddingFailure("TOKEN_LIMIT",true);
                transactions.yield(claim);return true;
            }
            int offset=0;while(offset<claim.chunks() && journal.points(id,offset,1).getFirst().confirmed())offset++;
            if(offset==claim.chunks()){transactions.publish(claim);return true;}
            var selected=new ArrayList<IndexPoint>();int max=0,total=0;
            for(var point:journal.points(id,offset,Math.min(4,claim.chunks()-offset))){
                int nextMax=Math.max(max,point.tokens());
                if(!selected.isEmpty() && (total+point.tokens()>6000 || nextMax*(selected.size()+1)>6000))break;
                selected.add(point);total+=point.tokens();max=nextMax;
            }
            var prior=journal.operations(id);
            final int first=offset;
            if(prior.stream().anyMatch(o->o.kind().equals("MODEL") && o.first()==first))throw new EmbeddingFailure("RESULT_NOT_DURABLE",true);
            var chunks=transactions.read(claim,offset,selected.size());if(chunks.isEmpty())return true;
            operation=transactions.begin(claim,"MODEL",selected);if(operation==null)return true;
            var result=model.embed(operation.id(),chunks.stream().map(TextChunk::text).toList(),selected.stream().map(IndexPoint::tokens).toList());
            EmbeddingSpec.vectors(result,selected.size());
            if(!transactions.acknowledge(claim,operation))return true;
            operation=transactions.begin(claim,"VECTOR",selected);if(operation==null)return true;
            vectors.upsert(claim,selected,result);
            if(!vectors.matches(claim,selected))throw new EmbeddingFailure("VECTOR_MANIFEST_MISMATCH",true);
            if(!transactions.acknowledge(claim,operation))return true;
            if(journal.confirmed(id,claim.chunks()))transactions.publish(claim);else transactions.yield(claim);
        }catch(EmbeddingFailure failure){
            boolean ended=operation==null || (operation.kind().equals("MODEL") ? failure.code().equals("MODEL_NOT_STARTED") || model.ended(operation.id()) : failure.ended());
            // A vector response validation failure follows a completed write; an HTTP failure remains UNKNOWN.
            transactions.fail(claim,operation,failure.code(),ended);
        }catch(BusinessException failure){transactions.fail(claim,operation,"TOKEN_CAPACITY",operation==null);}
        catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException failure){
            LOG.warn("Index step unconfirmed traceId={} indexId={} code=STATE_UNCONFIRMED",MDC.get("traceId"),id);
        }
        return true;
    }
    public void cleanup(long id){
        var work=transactions.cleanupClaim(id);if(work==null)return;
        var operations=journal.operations(id);
        for(var operation:operations)if(operation.kind().equals("MODEL") && Set.of("UNKNOWN","DISPATCHED").contains(operation.state()) && model.ended(operation.id()))transactions.modelEnded(work,operation);
        boolean anyVector=operations.stream().anyMatch(o->o.kind().equals("VECTOR"));
        try{
            int cursor=journal.cleanupCursor(id);
            var points=journal.points(id,cursor,4);
            if(anyVector && !points.isEmpty() && !vectors.deleteAndVerify(work,points))return;
            int next=cursor+points.size();
            if(next>=work.chunks()){
                transactions.cleaned(work);
                if(!journal.find(id).cleaned())journal.cleanupCursor(id,0);
            }else journal.cleanupCursor(id,next);
        }catch(EmbeddingFailure failure){LOG.warn("Index cleanup deferred traceId={} indexId={} code=CLEANUP_UNCONFIRMED",MDC.get("traceId"),id);}
    }
}
