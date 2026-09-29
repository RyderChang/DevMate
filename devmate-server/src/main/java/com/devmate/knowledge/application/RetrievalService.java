package com.devmate.knowledge.application;

import com.devmate.ai.embedding.*;
import com.devmate.common.api.ErrorCode;
import com.devmate.common.exception.BusinessException;
import com.devmate.knowledge.dto.RetrievalRequest;
import com.devmate.knowledge.retrieval.*;
import com.devmate.knowledge.vo.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;

/** One query vector, refreshed source filters, then bounded MySQL-authorized completion. */
@Service
public class RetrievalService {
    public static final String QUERY_PREFIX="Instruct: Given a software engineering question, retrieve relevant Java and Spring project documentation\nQuery: ";
    private static final Logger LOG=LoggerFactory.getLogger(RetrievalService.class);
    private final RetrievalTransactions transactions;
    private final EmbeddingGateway model;
    private final VectorSearch vectors;
    public RetrievalService(RetrievalTransactions transactions,EmbeddingGateway model,VectorSearch vectors){this.transactions=transactions;this.model=model;this.vectors=vectors;}
    private long elapsed(long start){return (System.nanoTime()-start)/1000000;}
    private RetrievalResponse response(String id,int tokens,int topK,int rounds,int inspected,boolean incomplete,String reason,List<RetrievalHit> hits){
        return new RetrievalResponse(EmbeddingSpec.ID,id,tokens,topK,rounds,inspected,incomplete,reason,"NORMALIZED_UNICODE_CODE_POINT",hits);
    }
    private Set<String> keys(List<RetrievalSource> sources){var keys=new HashSet<String>();sources.forEach(s->keys.add(s.key()));return keys;}
    private List<RetrievalHit> sorted(List<RetrievalHit> hits,int topK){
        return hits.stream().sorted(Comparator.comparingDouble(RetrievalHit::score).reversed().thenComparing(RetrievalHit::pointId)).limit(topK).toList();
    }
    public RetrievalResponse search(long owner,long project,RetrievalRequest request){
        String operation=null;boolean ended=false;long start=System.nanoTime();
        try{
            var sources=transactions.sources(owner,project);int topK=request.topK()==null?5:request.topK();
            if(topK<1 || topK>20 || request.query()==null || request.query().isBlank() || request.query().length()>8000)throw new BusinessException(ErrorCode.INVALID_PARAMETER);
            // JSON may encode an isolated surrogate even though the model requires complete UTF-8 scalar data.
            for(int i=0;i<request.query().length();i++)if(Character.isSurrogate(request.query().charAt(i))){if(!Character.isHighSurrogate(request.query().charAt(i)) || i+1==request.query().length() || !Character.isLowSurrogate(request.query().charAt(++i)))throw new BusinessException(ErrorCode.INVALID_PARAMETER);}
            if(sources.isEmpty())return response(null,0,topK,0,0,false,"NO_ACTIVE_SOURCES",List.of());
            String input=QUERY_PREFIX+request.query();var counts=model.count(List.of(input));
            EmbeddingSpec.counts(counts,1);int tokens=counts.getFirst();
            operation=transactions.begin(owner,project,EmbeddingSpec.sha(input),tokens);
            if(operation==null)return response(null,0,topK,0,0,false,"NO_ACTIVE_SOURCES",List.of());
            var encoded=model.embed(operation,List.of(input),counts);EmbeddingSpec.vectors(encoded,1);ended=true;
            if(!transactions.modelEnded(operation,elapsed(start)))throw new BusinessException(ErrorCode.DOCUMENT_RETRIEVAL_UNAVAILABLE);
            var checked=new LinkedHashMap<String,VectorCandidate>();List<RetrievalHit> hits=List.of();Set<String> lastSources=keys(sources);int rounds=0;boolean incomplete=true;String reason="BUDGET_EXHAUSTED";
            while(rounds<3 && checked.size()<200){
                sources=transactions.sources(owner,project);
                if(sources.isEmpty()){hits=List.of();lastSources=Set.of();incomplete=false;reason="NO_ACTIVE_SOURCES";break;}
                int limit=Math.min(100,200-checked.size());var before=keys(sources);
                var found=vectors.query(owner,project,EmbeddingSpec.ID,sources,encoded.getFirst(),Set.copyOf(checked.keySet()),limit);rounds++;
                if(found.size()>limit)throw new EmbeddingFailure("VECTOR_INVALID_RESPONSE",true);
                for(var candidate:found){
                    if(!before.contains(candidate.sourceKey()) || !Double.isFinite(candidate.score()) || Math.abs(candidate.score())>1.001 || checked.putIfAbsent(candidate.pointId(),candidate)!=null)throw new EmbeddingFailure("VECTOR_INVALID_RESPONSE",true);
                }
                var snapshot=transactions.snapshot(owner,project,checked.values());hits=sorted(snapshot.hits(),topK);var after=keys(snapshot.sources());lastSources=after;
                if(before.equals(after)){
                    if(hits.size()==topK){incomplete=false;reason="TOP_K";break;}
                    if(found.size()<limit){incomplete=false;reason="EXHAUSTED";break;}
                }else reason="SOURCES_CHANGED";
            }
            // Refresh accumulated hits once more immediately before returning; never retain an old round's body.
            var snapshot=transactions.snapshot(owner,project,checked.values());var finalHits=sorted(snapshot.hits(),topK);
            if(finalHits.size()<hits.size() || !lastSources.equals(keys(snapshot.sources()))){incomplete=true;reason="SOURCES_CHANGED";}
            if(!transactions.finish(operation,"SUCCEEDED",null,elapsed(start)))throw new BusinessException(ErrorCode.DOCUMENT_RETRIEVAL_UNAVAILABLE);
            return response(operation,tokens,topK,rounds,checked.size(),incomplete,reason,finalHits);
        }catch(RuntimeException error){
            if(operation!=null){
                boolean proof=ended || error instanceof EmbeddingFailure local && Set.of("LOCAL_NOT_SENT","MODEL_NOT_STARTED").contains(local.code()) || model.ended(operation);
                try{transactions.finish(operation,proof?"FAILED":"UNKNOWN",proof?"RETRIEVAL_FAILED":"MODEL_UNCONFIRMED",elapsed(start));}
                catch(RuntimeException stateError){LOG.warn("Retrieval state unconfirmed traceId={} retrievalId={} code=STATE_UNCONFIRMED",MDC.get("traceId"),operation);}
            }
            if(error instanceof BusinessException business)throw business;
            if(error instanceof org.springframework.dao.DataAccessException || error instanceof org.springframework.transaction.TransactionException)throw new BusinessException(ErrorCode.KNOWLEDGE_DATABASE_UNAVAILABLE);
            if(operation==null && error instanceof EmbeddingFailure failure && failure.code().equals("TOKEN_LIMIT"))throw new BusinessException(ErrorCode.INVALID_PARAMETER);
            throw new BusinessException(ErrorCode.DOCUMENT_RETRIEVAL_UNAVAILABLE);
        }
    }
}
