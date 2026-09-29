package com.devmate.knowledge.vo;

import java.util.List;

public record RetrievalResponse(String spec, String retrievalId, int queryTokens, int topK, int rounds,
        int inspectedPoints, boolean incomplete, String reason, String offsetUnit, List<RetrievalHit> hits) {}
