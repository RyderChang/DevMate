-- Independent location/debt ledger: no FK to deletable documents, processing or chunks.
CREATE TABLE knowledge_index_capacity (
    project_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    points BIGINT NOT NULL DEFAULT 0,
    bytes BIGINT NOT NULL DEFAULT 0,
    debts BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (project_id),
    CONSTRAINT ck_index_capacity CHECK (points>=0 AND bytes>=0 AND debts>=0
        AND ((project_id=0 AND owner_user_id=0 AND points<=5000000 AND bytes<=21474836480 AND debts<=100000)
        OR (project_id>0 AND owner_user_id>0 AND points<=500000 AND bytes<=2147483648 AND debts<=10000)))
);
INSERT INTO knowledge_index_capacity(project_id,owner_user_id) VALUES(0,0);

CREATE TABLE knowledge_index_daily_tokens (
    project_id BIGINT NOT NULL,
    utc_day DATE NOT NULL,
    tokens BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (project_id,utc_day),
    CONSTRAINT ck_index_daily_tokens CHECK (tokens>=0 AND ((project_id=0 AND tokens<=10000000) OR (project_id>0 AND tokens<=2000000)))
);

ALTER TABLE knowledge_documents ADD COLUMN active_index_id BIGINT NULL,
    ADD COLUMN next_index_generation BIGINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_document_index_generation CHECK (next_index_generation>0);

CREATE TABLE knowledge_indexes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    processing_id BIGINT NOT NULL,
    generation BIGINT NOT NULL,
    source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    spec VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    chunk_count INT NOT NULL,
    tokens BIGINT NOT NULL DEFAULT 0,
    reserved_bytes BIGINT NOT NULL,
    reserved_debts INT NOT NULL,
    cleaned BOOLEAN NOT NULL DEFAULT FALSE,
    cleanup_cursor INT NOT NULL DEFAULT 0,
    operation_version BIGINT NOT NULL DEFAULT 0,
    lease_owner CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until TIMESTAMP(6) NULL,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    error_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
    create_time TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_index_generation(document_id,generation),
    CONSTRAINT ck_index_state CHECK (state IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED','UNKNOWN','CLEANUP_REQUIRED')),
    CONSTRAINT ck_index_active CHECK (active IN (0,1) AND (active=0 OR state='SUCCEEDED')),
    CONSTRAINT ck_index_identity CHECK (owner_user_id>0 AND project_id>0 AND document_id>0 AND processing_id>0 AND generation>0
        AND spec='qwen3-0.6b-1024-cosine-v1' AND source_sha256 REGEXP '^[0-9a-f]{64}$' AND manifest_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_index_limits CHECK (chunk_count BETWEEN 1 AND 8192 AND tokens BETWEEN 0 AND 1000000
        AND reserved_bytes BETWEEN 0 AND 37748736 AND reserved_debts BETWEEN 0 AND 8193 AND operation_version>=0 AND cleanup_cursor BETWEEN 0 AND 8192),
    INDEX idx_index_due(state,next_attempt_at,id),
    INDEX idx_index_document(document_id,id),
    INDEX idx_index_cleanup(active,cleaned,next_attempt_at,id)
);

CREATE TABLE knowledge_index_points (
    index_id BIGINT NOT NULL,
    ordinal INT NOT NULL,
    point_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    chunk_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tokens INT NULL,
    confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (index_id,ordinal),
    UNIQUE KEY uk_index_point(point_id),
    CONSTRAINT fk_index_point FOREIGN KEY(index_id) REFERENCES knowledge_indexes(id) ON DELETE RESTRICT,
    CONSTRAINT ck_index_point CHECK (ordinal BETWEEN 0 AND 8191 AND chunk_sha256 REGEXP '^[0-9a-f]{64}$' AND (tokens IS NULL OR tokens BETWEEN 1 AND 6000))
);

-- Each operation freezes at most four consecutive point locations. Never stores vectors or text.
CREATE TABLE knowledge_index_operations (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    index_id BIGINT NOT NULL,
    kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    first_ordinal INT NOT NULL,
    point_count INT NOT NULL,
    manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tokens INT NOT NULL,
    utc_day DATE NOT NULL,
    state VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_version BIGINT NOT NULL,
    PRIMARY KEY(id),
    CONSTRAINT fk_index_operation FOREIGN KEY(index_id) REFERENCES knowledge_indexes(id) ON DELETE RESTRICT,
    CONSTRAINT ck_index_operation CHECK (kind IN ('MODEL','VECTOR') AND state IN ('DISPATCHED','CONFIRMED','ENDED','UNKNOWN')
        AND first_ordinal BETWEEN 0 AND 8191 AND point_count BETWEEN 1 AND 4 AND first_ordinal+point_count<=8192
        AND tokens BETWEEN 1 AND 6000 AND operation_version>=0 AND manifest_sha256 REGEXP '^[0-9a-f]{64}$'),
    INDEX idx_index_operation(index_id,kind,state,first_ordinal)
);

CREATE TABLE knowledge_index_requests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    index_id BIGINT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    creator BOOLEAN NOT NULL,
    accepted_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NULL,
    snapshot JSON NOT NULL,
    PRIMARY KEY(id),
    UNIQUE KEY uk_index_request(owner_user_id,project_id,client_request_id),
    CONSTRAINT fk_index_request FOREIGN KEY(index_id) REFERENCES knowledge_indexes(id) ON DELETE RESTRICT,
    CONSTRAINT ck_index_request CHECK (OCTET_LENGTH(snapshot)<=2048 AND fingerprint REGEXP '^[0-9a-f]{64}$'
        AND client_request_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        AND (creator=1 OR expires_at IS NOT NULL)),
    INDEX idx_index_request_document(owner_user_id,project_id,document_id),
    INDEX idx_index_request_expiry(expires_at,id)
);
