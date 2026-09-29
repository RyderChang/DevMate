ALTER TABLE ai_invocations
    ADD COLUMN mode VARCHAR(10) NOT NULL DEFAULT 'CHAT',
    ADD COLUMN request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN lease_expires_at TIMESTAMP(6) NULL,
    ADD CONSTRAINT ck_ai_invocations_mode CHECK (mode IN ('CHAT', 'RAG')),
    ADD CONSTRAINT ck_ai_invocations_fingerprint CHECK (request_sha256 IS NULL OR request_sha256 REGEXP '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_ai_invocations_rag_deadline CHECK (mode = 'CHAT' OR (request_sha256 IS NOT NULL AND lease_expires_at IS NOT NULL));

CREATE TABLE rag_record_capacity (
    scope VARCHAR(20) NOT NULL,
    scope_id BIGINT NOT NULL,
    records BIGINT NOT NULL DEFAULT 0,
    metadata_bytes BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_rag_record_capacity PRIMARY KEY (scope, scope_id),
    CONSTRAINT ck_rag_capacity_scope CHECK (
        (scope = 'GLOBAL' AND scope_id = 0) OR (scope IN ('PROJECT', 'CONVERSATION') AND scope_id > 0)),
    CONSTRAINT ck_rag_capacity_limit CHECK (records >= 0 AND records <= CASE scope
        WHEN 'GLOBAL' THEN 100000 WHEN 'PROJECT' THEN 10000 ELSE 1000 END),
    CONSTRAINT ck_rag_capacity_bytes CHECK (metadata_bytes = records * 5120)
);

CREATE TABLE rag_invocation_details (
    invocation_id BIGINT NOT NULL,
    execution_deadline TIMESTAMP(6) NOT NULL,
    retrieval_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    spec VARCHAR(100) NULL,
    query_tokens INT NULL,
    rounds INT NULL,
    inspected_points INT NULL,
    checked_at TIMESTAMP(6) NULL,
    chat_state VARCHAR(20) NOT NULL DEFAULT 'NOT_SENT',
    CONSTRAINT pk_rag_invocation_details PRIMARY KEY (invocation_id),
    CONSTRAINT fk_rag_details_invocation FOREIGN KEY (invocation_id) REFERENCES ai_invocations(id) ON DELETE RESTRICT,
    CONSTRAINT ck_rag_chat_state CHECK (chat_state IN ('NOT_SENT', 'DISPATCHED', 'RECEIVED', 'UNKNOWN')),
    CONSTRAINT ck_rag_retrieval_limits CHECK (
        (retrieval_id IS NULL AND spec IS NULL AND query_tokens IS NULL AND rounds IS NULL AND inspected_points IS NULL)
        OR (retrieval_id IS NOT NULL AND spec IS NOT NULL AND query_tokens IS NOT NULL
            AND rounds IS NOT NULL AND inspected_points IS NOT NULL AND spec = 'qwen3-0.6b-1024-cosine-v1'
            AND query_tokens BETWEEN 1 AND 6000 AND rounds BETWEEN 0 AND 3 AND inspected_points BETWEEN 0 AND 200)),
    INDEX idx_rag_details_deadline (execution_deadline, invocation_id)
);

CREATE TABLE rag_citations (
    invocation_id BIGINT NOT NULL,
    citation_id CHAR(2) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_snapshot JSON NOT NULL,
    CONSTRAINT pk_rag_citations PRIMARY KEY (invocation_id, citation_id),
    CONSTRAINT fk_rag_citation_invocation FOREIGN KEY (invocation_id) REFERENCES ai_invocations(id) ON DELETE RESTRICT,
    CONSTRAINT ck_rag_citation_id CHECK (citation_id IN ('C1', 'C2', 'C3', 'C4', 'C5')),
    CONSTRAINT ck_rag_citation_snapshot CHECK (JSON_TYPE(source_snapshot) = 'OBJECT')
);
