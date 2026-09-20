ALTER TABLE projects
    ADD CONSTRAINT uk_projects_id_owner UNIQUE (id, owner_user_id);

CREATE TABLE conversations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    generation_state VARCHAR(20) NOT NULL DEFAULT 'IDLE',
    generation_started_at TIMESTAMP(6) NULL,
    create_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_conversations PRIMARY KEY (id),
    CONSTRAINT fk_conversations_project_owner FOREIGN KEY (project_id, owner_user_id)
        REFERENCES projects (id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_conversations_title CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 200),
    CONSTRAINT ck_conversations_generation_state CHECK (generation_state IN ('IDLE', 'GENERATING')),
    CONSTRAINT ck_conversations_generation_lease CHECK (
        (generation_state = 'IDLE' AND generation_started_at IS NULL)
        OR (generation_state = 'GENERATING' AND generation_started_at IS NOT NULL)
    ),
    INDEX idx_conversations_owner_project_updated_id
        (owner_user_id, project_id, update_time DESC, id DESC)
);

CREATE TABLE conversation_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    sequence_no BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    create_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_conversation_messages PRIMARY KEY (id),
    CONSTRAINT fk_conversation_messages_conversation FOREIGN KEY (conversation_id)
        REFERENCES conversations (id) ON DELETE RESTRICT,
    CONSTRAINT uk_conversation_messages_sequence UNIQUE (conversation_id, sequence_no),
    CONSTRAINT uk_conversation_messages_conversation_id UNIQUE (conversation_id, id),
    CONSTRAINT ck_conversation_messages_sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_conversation_messages_role CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT ck_conversation_messages_content CHECK (CHAR_LENGTH(content) > 0),
    INDEX idx_conversation_messages_page (conversation_id, sequence_no, id)
);

CREATE TABLE ai_invocations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_message_id BIGINT NOT NULL,
    assistant_message_id BIGINT NULL,
    provider VARCHAR(50) NOT NULL,
    model VARCHAR(100) NOT NULL,
    prompt_template_version VARCHAR(100) NOT NULL,
    provider_request_id VARCHAR(255) NULL,
    status VARCHAR(20) NOT NULL,
    error_code VARCHAR(64) NULL,
    input_tokens INT NULL,
    output_tokens INT NULL,
    total_tokens INT NULL,
    duration_ms BIGINT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    CONSTRAINT pk_ai_invocations PRIMARY KEY (id),
    CONSTRAINT fk_ai_invocations_conversation FOREIGN KEY (conversation_id)
        REFERENCES conversations (id) ON DELETE RESTRICT,
    CONSTRAINT fk_ai_invocations_user_message FOREIGN KEY (conversation_id, user_message_id)
        REFERENCES conversation_messages (conversation_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_ai_invocations_assistant_message FOREIGN KEY (conversation_id, assistant_message_id)
        REFERENCES conversation_messages (conversation_id, id) ON DELETE RESTRICT,
    CONSTRAINT uk_ai_invocations_request UNIQUE (conversation_id, client_request_id),
    CONSTRAINT uk_ai_invocations_user_message UNIQUE (user_message_id),
    CONSTRAINT uk_ai_invocations_assistant_message UNIQUE (assistant_message_id),
    CONSTRAINT ck_ai_invocations_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_ai_invocations_completion CHECK (
        (status = 'PENDING' AND completed_at IS NULL AND assistant_message_id IS NULL AND error_code IS NULL)
        OR (status = 'SUCCEEDED' AND completed_at IS NOT NULL AND assistant_message_id IS NOT NULL AND error_code IS NULL)
        OR (status = 'FAILED' AND completed_at IS NOT NULL AND assistant_message_id IS NULL AND error_code IS NOT NULL)
    ),
    CONSTRAINT ck_ai_invocations_input_tokens CHECK (input_tokens IS NULL OR input_tokens >= 0),
    CONSTRAINT ck_ai_invocations_output_tokens CHECK (output_tokens IS NULL OR output_tokens >= 0),
    CONSTRAINT ck_ai_invocations_total_tokens CHECK (total_tokens IS NULL OR total_tokens >= 0),
    CONSTRAINT ck_ai_invocations_duration CHECK (duration_ms IS NULL OR duration_ms >= 0),
    INDEX idx_ai_invocations_conversation_status (conversation_id, status, started_at)
);
