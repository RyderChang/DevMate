-- Independent local query operation ledger; no query body, vectors or deletable parent FKs.
CREATE TABLE knowledge_retrieval_operations (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    spec VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    query_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tokens INT NOT NULL,
    utc_day DATE NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    error_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
    elapsed_ms BIGINT NOT NULL DEFAULT 0,
    accepted_at TIMESTAMP(6) NOT NULL,
    next_check_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY(id),
    CONSTRAINT ck_retrieval_operation CHECK (owner_user_id>0 AND project_id>0 AND spec='qwen3-0.6b-1024-cosine-v1'
        AND query_sha256 REGEXP '^[0-9a-f]{64}$' AND tokens BETWEEN 1 AND 6000 AND elapsed_ms>=0
        AND state IN ('DISPATCHED','MODEL_ENDED','SUCCEEDED','FAILED','UNKNOWN')),
    INDEX idx_retrieval_due(state,next_check_at,id),
    INDEX idx_retrieval_retention(state,accepted_at,id)
);
