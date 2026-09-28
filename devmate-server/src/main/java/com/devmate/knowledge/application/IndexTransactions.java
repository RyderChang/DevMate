package com.devmate.knowledge.application;

import com.devmate.ai.embedding.*;
import com.devmate.common.api.ErrorCode;
import com.devmate.common.exception.BusinessException;
import com.devmate.knowledge.config.IndexProperties;
import com.devmate.knowledge.infrastructure.*;
import com.devmate.knowledge.index.*;
import com.devmate.knowledge.vo.*;
import com.devmate.project.service.ProjectService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short database boundaries only: project -> document -> processing -> index -> global/project quota. */
@Service
public class IndexTransactions {
    private final IndexJournal journal;
    private final DocumentMapper documents;
    private final ProcessingMapper processing;
    private final ProcessingTransactions fragments;
    private final ProjectService projects;
    private final IndexProperties properties;
    private final Clock clock;
    private final ObjectMapper json;
    public IndexTransactions(IndexJournal journal,DocumentMapper documents,ProcessingMapper processing,ProcessingTransactions fragments,ProjectService projects,IndexProperties properties,@Qualifier("knowledgeClock") Clock clock,ObjectMapper json) {
        this.journal=journal;this.documents=documents;this.processing=processing;this.fragments=fragments;this.projects=projects;this.properties=properties;this.clock=clock;this.json=json;
    }
    public record Start(IndexResponse response,boolean accepted) {}
    public boolean enabled(){return properties.isEnabled() && fragments.enabled();}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    private void require(boolean condition){if(!condition) throw new BusinessException(ErrorCode.INTERNAL_ERROR);}
    private DocumentRow owned(long owner,long project,long document) {
        try{projects.lockOwnedActiveProject(owner,project);}catch(BusinessException e){if(e.getCode()==404)throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);throw e;}
        var row=documents.lockOwned(owner,project,document);
        if(row==null || row.storageState==StorageState.DELETE_PENDING)throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);return row;
    }
    private String manifest(List<TextChunk> chunks){return EmbeddingSpec.sha(chunks.stream().map(c->c.ordinal()+":"+c.sha256()+"\n").collect(java.util.stream.Collectors.joining()));}
    private String fingerprint(IndexWork work){return EmbeddingSpec.sha(work.document()+":"+work.sourceSha()+":"+work.processing()+":"+work.spec());}
    private String snapshot(IndexWork work){try{return json.writeValueAsString(IndexSummary.of(work));}catch(Exception error){throw new IllegalStateException("Safe metadata serialization failed");}}
    private IndexResponse response(IndexSummary latest,long document){var active=enabled()?journal.active(document):null;return new IndexResponse(latest,IndexSummary.of(active),active!=null);}
    @Transactional public Start start(long owner,long project,long document,String request) {
        var source=owned(owner,project,document);
        if(!enabled())throw new BusinessException(ErrorCode.DOCUMENT_INDEX_DISABLED);
        journal.expireRequest(owner,project,request,now());
        var previous=journal.request(owner,project,request,now());
        if(previous!=null){
            if(previous.document()!=document)throw new BusinessException(ErrorCode.DOCUMENT_INDEX_CONFLICT);
            var work=journal.find(previous.index());
            if(work!=null && !work.sourceSha().equals(source.sha256))throw new BusinessException(ErrorCode.DOCUMENT_INDEX_CONFLICT);
            try {
                var latest=work==null?json.readValue(previous.snapshot(),IndexSummary.class):IndexSummary.of(work);
                return new Start(response(latest,document),latest.state().equals("PENDING") || latest.state().equals("RUNNING"));
            }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Safe metadata deserialization failed");}
        }
        if(journal.requestCount(owner,project,null)>=10000 || journal.requestCount(owner,project,document)>=100)throw new BusinessException(ErrorCode.DOCUMENT_INDEX_REQUEST_LIMIT);
        var activeProcessing=processing.active(document);
        if(source.storageState!=StorageState.STORED || activeProcessing==null)throw new BusinessException(ErrorCode.DOCUMENT_NOT_CHUNKED);
        processing.lock(activeProcessing.id);
        if(journal.inFlight(document))throw new BusinessException(ErrorCode.DOCUMENT_INDEX_IN_PROGRESS);
        var active=journal.active(document);
        if(active!=null && EmbeddingSpec.ID.equals(active.spec())){
            journal.bind(active,request,fingerprint(active),snapshot(active),false,now());return new Start(response(IndexSummary.of(active),document),false);
        }
        if(journal.generations(document)>=4)throw new BusinessException(ErrorCode.DOCUMENT_INDEX_CAPACITY_EXCEEDED);
        var chunks=new ArrayList<TextChunk>();
        for(int offset=0;offset<activeProcessing.chunkCount;offset+=100)chunks.addAll(fragments.readActive(owner,project,document,offset,100));
        require(chunks.size()==activeProcessing.chunkCount && chunks.size()>0);
        for(int i=0;i<chunks.size();i++)require(chunks.get(i).ordinal()==i && TextChunker.sha(chunks.get(i).text().getBytes(StandardCharsets.UTF_8)).equals(chunks.get(i).sha256()));
        long bytes=chunks.size()*4608L;int debts=chunks.size()+1;
        if(!journal.reserve(owner,project,chunks.size(),bytes,debts))throw new BusinessException(ErrorCode.DOCUMENT_INDEX_CAPACITY_EXCEEDED);
        long id=journal.insert(owner,project,document,activeProcessing.id,source.sha256,manifest(chunks),EmbeddingSpec.ID,chunks.size(),bytes,debts,now());
        for(var chunk:chunks){String identity=owner+"/"+project+"/"+document+"/"+activeProcessing.id+"/"+id+"/"+EmbeddingSpec.ID+"/"+chunk.ordinal();
            journal.insertPoint(id,new IndexPoint(chunk.ordinal(),UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString(),chunk.sha256(),null,false));}
        var work=journal.find(id);journal.bind(work,request,fingerprint(work),snapshot(work),true,now());
        return new Start(response(IndexSummary.of(work),document),true);
    }
    @Transactional public IndexResponse status(long owner,long project,long document){owned(owner,project,document);return response(IndexSummary.of(journal.latest(document)),document);}
    private IndexWork lock(IndexWork hint) {
        projects.lockProjectForMaintenance(hint.owner(),hint.project());documents.lock(hint.document());
        if(processing.find(hint.processing())!=null)processing.lock(hint.processing());return journal.lock(hint.id());
    }
    private boolean eligible(IndexWork work) {
        var document=documents.find(work.document());var active=processing.active(work.document());
        return projects.lockProjectForMaintenance(work.owner(),work.project()) && document!=null && document.storageState==StorageState.STORED
                && work.sourceSha().equals(document.sha256) && active!=null && active.id==work.processing() && active.chunkCount==work.chunks();
    }
    private IndexWork guarded(IndexWork claim) {
        var work=lock(claim);
        if(work==null || !work.state().equals("RUNNING") || work.version()!=claim.version() || !Objects.equals(work.lease(),claim.lease()) || work.leaseUntil()==null || !work.leaseUntil().isAfter(now()))return null;
        if(!eligible(work)){journal.cancel(work.document(),now());return null;}
        if(!enabled()){journal.state(work.id(),"PENDING",null,now().plusMinutes(1));return null;}return work;
    }
    @Transactional public IndexWork claim(long id) {
        var hint=journal.find(id);if(hint==null)return null;var work=lock(hint);
        if(work==null || !Set.of("PENDING","RUNNING").contains(work.state()) || work.leaseUntil()!=null && work.leaseUntil().isAfter(now()) || !enabled())return null;
        if(!eligible(work)){journal.cancel(work.document(),now());return null;}
        if(journal.operations(id).stream().anyMatch(o->o.state().equals("DISPATCHED") || o.state().equals("UNKNOWN"))){journal.state(id,"UNKNOWN","OPERATION_UNCONFIRMED",now());return null;}
        journal.claim(id,UUID.randomUUID().toString(),now().plusMinutes(7));return journal.find(id);
    }
    @Transactional public List<TextChunk> read(IndexWork claim,int offset,int limit) {
        if(guarded(claim)==null)return List.of();
        var chunks=fragments.readActive(claim.owner(),claim.project(),claim.document(),offset,limit);
        var points=journal.points(claim.id(),offset,limit);require(chunks.size()==points.size());
        for(int i=0;i<chunks.size();i++)require(chunks.get(i).ordinal()==points.get(i).ordinal() && chunks.get(i).sha256().equals(points.get(i).sha()));return chunks;
    }
    @Transactional public void counted(IndexWork claim,int offset,List<Integer> counts) {
        if(guarded(claim)==null)return;
        for(int i=0;i<counts.size();i++){if(counts.get(i)==null || counts.get(i)<1 || counts.get(i)>6000)throw new EmbeddingFailure("TOKEN_LIMIT",true);journal.count(claim.id(),offset+i,counts.get(i));}
    }
    @Transactional public IndexOperation begin(IndexWork claim,String kind,List<IndexPoint> points) {
        var work=guarded(claim);if(work==null)return null;
        int tokens=points.stream().mapToInt(IndexPoint::tokens).sum();EmbeddingSpec.counts(points.stream().map(IndexPoint::tokens).toList(),points.size());
        if(kind.equals("MODEL") && !journal.reserveTokens(work,tokens,now().toLocalDate()))throw new BusinessException(ErrorCode.DOCUMENT_INDEX_TOKEN_LIMIT);
        String digest=EmbeddingSpec.sha(points.stream().map(p->p.ordinal()+":"+p.id()+":"+p.sha()+"\n").collect(java.util.stream.Collectors.joining()));
        var operation=new IndexOperation(UUID.randomUUID().toString(),work.id(),kind,points.getFirst().ordinal(),points.size(),digest,tokens,"DISPATCHED",work.version());
        journal.operation(operation,now().toLocalDate());return operation;
    }
    @Transactional public boolean acknowledge(IndexWork claim,IndexOperation op) {
        if(guarded(claim)==null)return false;journal.operationState(op.id(),"CONFIRMED");
        if(op.kind().equals("VECTOR"))journal.confirm(claim.id(),op.first(),op.count());return true;
    }
    @Transactional public void fail(IndexWork claim,IndexOperation op,String code,boolean ended) {
        if(guarded(claim)==null)return;
        if(op!=null)journal.operationState(op.id(),ended?"ENDED":"UNKNOWN");
        journal.state(claim.id(),ended?"FAILED":"UNKNOWN",code,now());journal.snapshots(claim.id(),snapshot(journal.find(claim.id())));
    }
    @Transactional public void yield(IndexWork claim) {if(guarded(claim)!=null)journal.state(claim.id(),"PENDING",null,now());}
    @Transactional public boolean publish(IndexWork claim) {
        var work=guarded(claim);if(work==null || !journal.confirmed(work.id(),work.chunks()))return false;
        var chunks=new ArrayList<TextChunk>();for(int offset=0;offset<work.chunks();offset+=100)chunks.addAll(fragments.readActive(work.owner(),work.project(),work.document(),offset,100));
        require(chunks.size()==work.chunks() && manifest(chunks).equals(work.manifest()));
        journal.publish(work);journal.snapshots(work.id(),snapshot(journal.find(work.id())));return true;
    }
    @Transactional public IndexWork cleanupClaim(long id) {
        var hint=journal.find(id);if(hint==null)return null;var work=lock(hint);
        if(work==null || work.active() || work.cleaned() || Set.of("PENDING","RUNNING").contains(work.state()))return null;
        journal.defer(id,now());return work;
    }
    @Transactional public void modelEnded(IndexWork work,IndexOperation op) {
        var current=lock(work);if(current==null || current.active() || current.cleaned())return;
        if(op.kind().equals("MODEL"))journal.operationState(op.id(),"ENDED");
    }
    @Transactional public void cleaned(IndexWork work) {
        var current=lock(work);if(current==null || current.active() || current.cleaned() || current.version()!=work.version())return;
        if(journal.operations(work.id()).stream().anyMatch(o->o.state().equals("UNKNOWN") || o.state().equals("DISPATCHED")))return;
        journal.clean(current);journal.snapshots(current.id(),snapshot(journal.find(current.id())));
    }
    @Transactional public void maintain() {journal.purge(now());}
}
