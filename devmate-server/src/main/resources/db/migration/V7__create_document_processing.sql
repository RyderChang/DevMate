CREATE TABLE knowledge_processing_capacity (
    project_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    charged_bytes BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (project_id),
    CONSTRAINT fk_processing_capacity_owner FOREIGN KEY (project_id, owner_user_id)
        REFERENCES projects (id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_processing_capacity_bytes CHECK (charged_bytes BETWEEN 0 AND 268435456)
);

ALTER TABLE knowledge_documents ADD COLUMN next_processing_generation BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN active_processing_id BIGINT NULL,
    ADD CONSTRAINT ck_document_processing_generation CHECK (next_processing_generation > 0);

CREATE TABLE knowledge_processing (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    generation BIGINT NOT NULL,
    source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    normalized_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    parser_version VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    strategy_version VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    active TINYINT(1) NOT NULL DEFAULT 0,
    active_document_id BIGINT GENERATED ALWAYS AS (IF(active=1,document_id,NULL)) STORED,
    chunk_count INT NOT NULL DEFAULT 0,
    text_bytes BIGINT NOT NULL DEFAULT 0,
    used_bytes BIGINT NOT NULL DEFAULT 0,
    reserved_bytes BIGINT NOT NULL DEFAULT 8388608,
    operation_version BIGINT NOT NULL DEFAULT 0,
    lease_owner CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until TIMESTAMP(6) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    error_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
    create_time TIMESTAMP(6) NOT NULL,
    update_time TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_processing_generation UNIQUE (document_id, generation),
    CONSTRAINT uk_processing_active_document UNIQUE (active_document_id),
    CONSTRAINT uk_processing_owner UNIQUE (id, document_id, project_id, owner_user_id),
    CONSTRAINT fk_processing_document_owner FOREIGN KEY (document_id, project_id, owner_user_id)
        REFERENCES knowledge_documents (id, project_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_processing_state CHECK (state IN ('PENDING','PROCESSING','CHUNKED','FAILED','CANCELLED')),
    CONSTRAINT ck_processing_active CHECK (active IN (0,1) AND (active=0 OR state='CHUNKED')),
    CONSTRAINT ck_processing_sha CHECK (source_sha256 REGEXP '^[0-9a-f]{64}$'
        AND (normalized_sha256 IS NULL OR normalized_sha256 REGEXP '^[0-9a-f]{64}$')),
    CONSTRAINT ck_processing_published CHECK (state<>'CHUNKED' OR (normalized_sha256 IS NOT NULL AND chunk_count>0)),
    CONSTRAINT ck_processing_error CHECK (error_code IS NULL OR error_code IN ('INTEGRITY_MISMATCH','INVALID_TEXT',
        'CHUNK_LIMIT_EXCEEDED','PROCESSING_TIMEOUT','STORAGE_TIMEOUT','STORAGE_UNAVAILABLE','DOCUMENT_DELETED','PROCESSING_VERSION_UNAVAILABLE')),
    CONSTRAINT ck_processing_terminal CHECK (state NOT IN ('FAILED','CANCELLED') OR error_code IS NOT NULL),
    CONSTRAINT ck_processing_limits CHECK (generation>0 AND operation_version>=0 AND retry_count BETWEEN 0 AND 3 AND attempt_count>=0
        AND chunk_count BETWEEN 0 AND 8192 AND text_bytes BETWEEN 0 AND 8388608
        AND used_bytes>=0 AND reserved_bytes>=0 AND used_bytes+reserved_bytes<=8388608),
    CONSTRAINT ck_processing_lease CHECK ((lease_owner IS NULL AND lease_until IS NULL)
        OR (lease_owner IS NOT NULL AND lease_until IS NOT NULL AND state='PROCESSING')),
    INDEX idx_processing_due (state, next_attempt_at, id),
    INDEX idx_processing_cleanup (active, state, id)
);

ALTER TABLE knowledge_documents ADD CONSTRAINT fk_document_active_processing
    FOREIGN KEY (active_processing_id, id, project_id, owner_user_id)
        REFERENCES knowledge_processing (id, document_id, project_id, owner_user_id) ON DELETE RESTRICT;

CREATE TABLE knowledge_chunks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    processing_id BIGINT NOT NULL,
    ordinal INT NOT NULL,
    start_offset INT NOT NULL,
    end_offset INT NOT NULL,
    start_line INT NOT NULL,
    end_line INT NOT NULL,
    text MEDIUMTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    byte_size INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_chunk_ordinal UNIQUE (processing_id, ordinal),
    CONSTRAINT fk_chunk_processing_owner FOREIGN KEY (processing_id, document_id, project_id, owner_user_id)
        REFERENCES knowledge_processing (id, document_id, project_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_chunk_position CHECK (ordinal BETWEEN 0 AND 8191 AND start_offset>=0 AND end_offset>start_offset
        AND end_offset-start_offset<=1200 AND start_line>0 AND end_line>=start_line
        AND CHAR_LENGTH(text)=end_offset-start_offset AND OCTET_LENGTH(text)=byte_size),
    CONSTRAINT ck_chunk_hash CHECK (sha256 REGEXP '^[0-9a-f]{64}$')
);

-- Document identity survives physical parent removal for the bounded 24-hour replay window.
CREATE TABLE knowledge_processing_requests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    processing_id BIGINT NULL,
    generation BIGINT NOT NULL,
    source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parser_version VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    strategy_version VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    terminal_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    error_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
    creator TINYINT(1) NOT NULL,
    expires_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_processing_request UNIQUE (owner_user_id, project_id, client_request_id),
    CONSTRAINT fk_processing_request_owner FOREIGN KEY (project_id, owner_user_id)
        REFERENCES projects (id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_processing_request_generation FOREIGN KEY (processing_id, document_id, project_id, owner_user_id)
        REFERENCES knowledge_processing (id, document_id, project_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_processing_request_uuid CHECK (client_request_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    CONSTRAINT ck_processing_request_hash CHECK (fingerprint REGEXP '^[0-9a-f]{64}$' AND source_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_processing_request_state CHECK (terminal_state IN ('PENDING','PROCESSING','CHUNKED','FAILED','CANCELLED')),
    CONSTRAINT ck_processing_request_retention CHECK (creator IN (0,1) AND generation>0
        AND (creator=1 OR expires_at IS NOT NULL) AND (processing_id IS NOT NULL OR expires_at IS NOT NULL)),
    INDEX idx_processing_request_document (document_id, owner_user_id, project_id),
    INDEX idx_processing_request_expiry (expires_at, id)
);
