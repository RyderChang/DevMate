package com.devmate.conversation.vo;

import com.devmate.knowledge.vo.RetrievalHit;

/** Immutable server provenance; deliberately contains neither source text nor model-provided metadata. */
public record CitationSource(String pointId, long documentId, String filename, long processingId, long indexId,
        long processingGeneration, long indexGeneration, String parserVersion, String strategyVersion,
        String sourceSha256, String chunkSha256, int ordinal, int start, int end, int startLine, int endLine) {
    public static CitationSource from(RetrievalHit h) {
        return new CitationSource(h.pointId(), h.documentId(), h.filename(), h.processingId(), h.indexId(),
                h.processingGeneration(), h.indexGeneration(), h.parserVersion(), h.strategyVersion(),
                h.sourceSha256(), h.chunkSha256(), h.ordinal(), h.start(), h.end(), h.startLine(), h.endLine());
    }
    public RetrievalHit location() {
        return new RetrievalHit(pointId, 0, documentId, filename, processingId, indexId, processingGeneration,
                indexGeneration, parserVersion, strategyVersion, sourceSha256, chunkSha256, ordinal,
                start, end, startLine, endLine, null);
    }
}
