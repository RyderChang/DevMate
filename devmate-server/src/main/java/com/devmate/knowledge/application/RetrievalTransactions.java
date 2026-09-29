package com.devmate.knowledge.application;

import com.devmate.ai.embedding.*;
import com.devmate.common.api.ErrorCode;
import com.devmate.common.exception.BusinessException;
import com.devmate.knowledge.config.*;
import com.devmate.knowledge.infrastructure.*;
import com.devmate.knowledge.retrieval.*;
import com.devmate.knowledge.vo.RetrievalHit;
import com.devmate.project.service.ProjectService;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Project authorization and quota updates only; no remote work inside a database transaction. */
@Service
public class RetrievalTransactions {
    private final RetrievalJournal journal;
    private final IndexJournal capacity;
    private final ProjectService projects;
    private final RetrievalProperties properties;
    private final IndexProperties indexing;
    private final ProcessingTransactions processing;
    private final Clock clock;
    public RetrievalTransactions(RetrievalJournal journal,IndexJournal capacity,ProjectService projects,RetrievalProperties properties,IndexProperties indexing,ProcessingTransactions processing,@Qualifier("knowledgeClock") Clock clock){
        this.journal=journal;this.capacity=capacity;this.projects=projects;this.properties=properties;this.indexing=indexing;this.processing=processing;this.clock=clock;
    }
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    private void owned(long owner,long project){
        projects.lockOwnedActiveProject(owner,project);
        if(!properties.isEnabled() || !indexing.isEnabled() || !processing.enabled())throw new BusinessException(ErrorCode.DOCUMENT_RETRIEVAL_DISABLED);
    }
    @Transactional public List<RetrievalSource> sources(long owner,long project){owned(owner,project);return journal.sources(owner,project,EmbeddingSpec.ID);}
    @Transactional public List<RetrievalHit> hydrate(long owner,long project,Collection<VectorCandidate> candidates){owned(owner,project);return journal.hydrate(owner,project,EmbeddingSpec.ID,candidates);}
    public record Snapshot(List<RetrievalSource> sources,List<RetrievalHit> hits){}
    @Transactional public Snapshot snapshot(long owner,long project,Collection<VectorCandidate> candidates){
        owned(owner,project);return new Snapshot(journal.sources(owner,project,EmbeddingSpec.ID),journal.hydrate(owner,project,EmbeddingSpec.ID,candidates));
    }
    @Transactional public String begin(long owner,long project,String hash,int tokens){
        owned(owner,project);if(journal.sources(owner,project,EmbeddingSpec.ID).isEmpty())return null;
        if(tokens<1 || tokens>6000)throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        if(!capacity.reserve(owner,project,0,4096,1) || !journal.reserveTokens(project,tokens,now().toLocalDate()))throw new BusinessException(ErrorCode.DOCUMENT_RETRIEVAL_LIMIT);
        String id=UUID.randomUUID().toString();journal.insert(id,owner,project,hash,tokens,now());return id;
    }
    @Transactional public boolean modelEnded(String id,long elapsed){
        var op=journal.lock(id);if(op==null || Set.of("SUCCEEDED","FAILED").contains(op.state()))return false;
        journal.state(id,"MODEL_ENDED",null,elapsed,now().plusMinutes(7));return true;
    }
    @Transactional public boolean finish(String id,String state,String code,long elapsed){
        var op=journal.lock(id);if(op==null || Set.of("SUCCEEDED","FAILED").contains(op.state()))return false;
        journal.state(id,state,code,elapsed,now().plusMinutes(5));return true;
    }
    @Transactional public String recover(String id,boolean ended){
        var op=journal.lock(id);if(op==null || Set.of("SUCCEEDED","FAILED").contains(op.state()))return null;
        if(ended || op.state().equals("MODEL_ENDED"))journal.state(id,"FAILED","RESULT_NOT_DURABLE",0,now().plusMinutes(5));
        else journal.state(id,"UNKNOWN","MODEL_UNCONFIRMED",0,now().plusMinutes(5));return op.state();
    }
    @Transactional public void expire(String id){
        var op=journal.lock(id);if(op==null || !Set.of("SUCCEEDED","FAILED").contains(op.state()) || op.accepted().isAfter(now().minusHours(24)))return;
        journal.remove(op);
    }
}
