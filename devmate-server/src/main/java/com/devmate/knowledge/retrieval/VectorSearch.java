package com.devmate.knowledge.retrieval;

import java.util.List;
import java.util.Set;

public interface VectorSearch {
    List<VectorCandidate> query(long owner, long project, String spec, List<RetrievalSource> sources,
            float[] vector, Set<String> excludedPoints, int limit);
}
