CREATE TABLE knowledge_project_capacity (
    project_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    reserved_documents INT NOT NULL DEFAULT 0,
    reserved_bytes BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_knowledge_project_capacity PRIMARY KEY (project_id),
    CONSTRAINT fk_knowledge_capacity_project_owner FOREIGN KEY (project_id, owner_user_id)
        REFERENCES projects (id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_knowledge_capacity_documents CHECK (reserved_documents BETWEEN 0 AND 100),
    CONSTRAINT ck_knowledge_capacity_bytes CHECK (reserved_bytes BETWEEN 0 AND 104857600)
);

CREATE TABLE knowledge_documents (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    filename VARCHAR(200) NOT NULL,
    file_type VARCHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    byte_size BIGINT NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_type VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UPLOAD',
    bucket VARCHAR(63) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    put_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    storage_state VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    remote_phase VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    operation_version BIGINT NOT NULL DEFAULT 0,
    lease_owner CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until TIMESTAMP(6) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    needs_manual TINYINT(1) NOT NULL DEFAULT 0,
    create_time TIMESTAMP(6) NOT NULL,
    update_time TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_knowledge_documents PRIMARY KEY (id),
    CONSTRAINT uk_knowledge_document_owner UNIQUE (id, project_id, owner_user_id),
    CONSTRAINT uk_knowledge_document_key UNIQUE (bucket, object_key),
    CONSTRAINT uk_knowledge_document_put_token UNIQUE (put_token),
    CONSTRAINT fk_knowledge_document_user FOREIGN KEY (owner_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_knowledge_document_project_owner FOREIGN KEY (project_id, owner_user_id)
        REFERENCES projects (id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_knowledge_document_name CHECK (CHAR_LENGTH(filename) BETWEEN 1 AND 200),
    CONSTRAINT ck_knowledge_document_type CHECK (file_type IN ('txt', 'md') AND source_type='UPLOAD'),
    CONSTRAINT ck_knowledge_document_bytes CHECK (byte_size BETWEEN 1 AND 5242880),
    CONSTRAINT ck_knowledge_document_sha CHECK (sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_knowledge_document_state CHECK (storage_state IN ('UPLOADING', 'STORED', 'FAILED', 'DELETE_PENDING')),
    CONSTRAINT ck_knowledge_document_remote_phase CHECK (remote_phase IN ('NOT_STARTED', 'POSSIBLE', 'FINISHED')),
    CONSTRAINT ck_knowledge_document_stored CHECK (storage_state <> 'STORED' OR remote_phase='FINISHED'),
    CONSTRAINT ck_knowledge_document_failure CHECK (storage_state <> 'FAILED' OR error_code IS NOT NULL),
    CONSTRAINT ck_knowledge_document_error CHECK (error_code IS NULL OR error_code IN ('PROJECT_DELETED', 'INTEGRITY_MISMATCH',
        'UPLOAD_ABORTED_OR_INVALID', 'WRITE_OUTCOME_UNKNOWN', 'STORAGE_TIMEOUT', 'STORAGE_UNAVAILABLE', 'DELETE_UNCONFIRMED')),
    CONSTRAINT ck_knowledge_document_operation CHECK (operation_version >= 0 AND retry_count BETWEEN 0 AND 5 AND needs_manual IN (0,1)),
    CONSTRAINT ck_knowledge_document_lease CHECK ((lease_owner IS NULL AND lease_until IS NULL)
        OR (lease_owner IS NOT NULL AND lease_until IS NOT NULL)),
    INDEX idx_knowledge_document_list (owner_user_id, project_id, create_time DESC, id DESC, storage_state),
    INDEX idx_knowledge_document_recovery (needs_manual, next_attempt_at, id, storage_state),
    INDEX idx_knowledge_document_project_cleanup (project_id, owner_user_id, storage_state, id)
);

CREATE TABLE knowledge_document_requests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document_id BIGINT NULL,
    terminal_state VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
    expires_at TIMESTAMP(6) NULL,
    CONSTRAINT pk_knowledge_document_requests PRIMARY KEY (id),
    CONSTRAINT uk_knowledge_document_request UNIQUE (owner_user_id, project_id, client_request_id),
    CONSTRAINT uk_knowledge_document_request_document UNIQUE (document_id),
    CONSTRAINT fk_knowledge_request_user FOREIGN KEY (owner_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_knowledge_request_project_owner FOREIGN KEY (project_id, owner_user_id)
        REFERENCES projects (id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_knowledge_request_document_owner FOREIGN KEY (document_id, project_id, owner_user_id)
        REFERENCES knowledge_documents (id, project_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_knowledge_request_state CHECK ((terminal_state='ACTIVE' AND document_id IS NOT NULL AND expires_at IS NULL)
        OR (terminal_state IN ('FAILED','DELETED') AND document_id IS NULL AND expires_at IS NOT NULL)),
    CONSTRAINT ck_knowledge_request_uuid CHECK (client_request_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    CONSTRAINT ck_knowledge_request_fingerprint CHECK (fingerprint REGEXP '^[0-9a-f]{64}$'),
    INDEX idx_knowledge_request_expiry (expires_at, id)
);
