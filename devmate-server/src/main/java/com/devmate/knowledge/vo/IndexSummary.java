package com.devmate.knowledge.vo;

import com.devmate.knowledge.index.IndexWork;

public record IndexSummary(long indexId,long processingId,long generation,String spec,String state,String sourceSha256,String manifestSha256,int chunkCount,long tokens,String errorCode) {
    public static IndexSummary of(IndexWork work){return work==null?null:new IndexSummary(work.id(),work.processing(),work.generation(),work.spec(),work.state(),work.sourceSha(),work.manifest(),work.chunks(),work.tokens(),work.error());}
}
