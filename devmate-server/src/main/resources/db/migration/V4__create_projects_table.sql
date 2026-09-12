CREATE TABLE projects (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000) NULL,
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    create_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    delete_time TIMESTAMP(6) NULL,
    CONSTRAINT pk_projects PRIMARY KEY (id),
    CONSTRAINT fk_projects_owner_user FOREIGN KEY (owner_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_projects_deleted CHECK (deleted IN (0, 1)),
    INDEX idx_projects_owner_deleted_updated_id (owner_user_id, deleted, update_time DESC, id DESC)
);
