package com.devmate.knowledge.retrieval;

/** The complete published tuple, never independent lists of generation IDs. */
public record RetrievalSource(long owner, long project, long document, long processing, long index,
        String spec, String sourceSha, String filename, long processingGeneration, long indexGeneration,
        String parserVersion, String strategyVersion) {
    public String key() { return document + "/" + processing + "/" + index + "/" + spec; }
}
