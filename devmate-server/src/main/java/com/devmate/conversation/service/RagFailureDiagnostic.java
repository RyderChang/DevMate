package com.devmate.conversation.service;

import com.devmate.ai.application.AiGatewayException;

/** Fixed, content-free values persisted only for classified RAG response failures. */
record RagFailureDiagnostic(String stage, String category) {
    static RagFailureDiagnostic gateway(AiGatewayException.ResponseIssue issue) {
        var safe = issue == null ? AiGatewayException.ResponseIssue.UNCLASSIFIED : issue;
        return new RagFailureDiagnostic(safe == AiGatewayException.ResponseIssue.UPSTREAM_STATUS
                ? "PROVIDER_HTTP" : "PROVIDER_RESPONSE", safe.name());
    }
    static RagFailureDiagnostic output(RagOutputException.Issue issue) {
        return new RagFailureDiagnostic("RAG_OUTPUT", issue.name());
    }
}
