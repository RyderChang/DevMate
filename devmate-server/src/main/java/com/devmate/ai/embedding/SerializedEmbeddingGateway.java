package com.devmate.ai.embedding;

import java.util.List;
import java.util.concurrent.Semaphore;

/** Indexing and interactive queries share one local inference slot; contention never queues. */
public final class SerializedEmbeddingGateway implements EmbeddingGateway,AutoCloseable {
    private final EmbeddingGateway delegate;
    private final Semaphore slot=new Semaphore(1);
    public SerializedEmbeddingGateway(EmbeddingGateway delegate){this.delegate=delegate;}
    @Override public List<Integer> count(List<String> texts){return delegate.count(texts);}
    @Override public List<float[]> embed(String operation,List<String> texts,List<Integer> counts){
        if(!slot.tryAcquire())throw new EmbeddingFailure("LOCAL_NOT_SENT",true);
        try{return delegate.embed(operation,texts,counts);}finally{slot.release();}
    }
    @Override public boolean ended(String operation){return delegate.ended(operation);}
    @Override public void close()throws Exception{if(delegate instanceof AutoCloseable client)client.close();}
}
