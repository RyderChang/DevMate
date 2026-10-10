ALTER TABLE rag_invocation_details
    DROP CHECK ck_rag_failure_diagnostic,
    ADD CONSTRAINT ck_rag_failure_diagnostic CHECK (
        (failure_stage IS NULL AND failure_category IS NULL)
        OR (failure_stage IS NOT NULL AND failure_category IS NOT NULL AND (
          (failure_stage = 'PROVIDER_HTTP' AND failure_category = 'UPSTREAM_STATUS')
        OR (failure_stage = 'PROVIDER_RESPONSE' AND failure_category IN (
            'RESPONSE_SIZE', 'RESPONSE_ENCODING', 'RESPONSE_ENVELOPE',
            'FINISH_REASON', 'MESSAGE_CONTENT', 'USAGE', 'UNCLASSIFIED'))
        OR (failure_stage = 'RAG_OUTPUT' AND failure_category IN (
            'JSON_SCHEMA', 'JSON_SIZE', 'JSON_DUPLICATE_KEY',
            'JSON_SYNTAX', 'JSON_TRAILING', 'JSON_SHAPE',
            'ANSWER_CONTENT', 'CITATION_IDS', 'CITATION_MARKERS'))))
    );
