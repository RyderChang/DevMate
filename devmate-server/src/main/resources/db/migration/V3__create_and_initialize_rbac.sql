CREATE TABLE `role` (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL,
    code VARCHAR(50) NOT NULL,
    description VARCHAR(255) NULL,
    create_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_role PRIMARY KEY (id),
    CONSTRAINT uk_role_code UNIQUE (code)
);

CREATE TABLE `permission` (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL,
    code VARCHAR(50) NOT NULL,
    description VARCHAR(255) NULL,
    create_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_permission PRIMARY KEY (id),
    CONSTRAINT uk_permission_code UNIQUE (code)
);

CREATE TABLE user_role (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    CONSTRAINT pk_user_role PRIMARY KEY (id),
    CONSTRAINT uk_user_role_user_role UNIQUE (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES `role` (id) ON DELETE RESTRICT,
    INDEX idx_user_role_role_id (role_id)
);

CREATE TABLE role_permission (
    id BIGINT NOT NULL AUTO_INCREMENT,
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    CONSTRAINT pk_role_permission PRIMARY KEY (id),
    CONSTRAINT uk_role_permission_role_permission UNIQUE (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES `role` (id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES `permission` (id) ON DELETE RESTRICT,
    INDEX idx_role_permission_permission_id (permission_id)
);

INSERT INTO `role` (name, code, description) VALUES
    ('User', 'USER', 'Default application user'),
    ('Administrator', 'ADMIN', 'System administrator');

INSERT INTO `permission` (name, code, description) VALUES
    ('User access', 'user', 'Access standard user capabilities'),
    ('System access', 'system', 'Access system administration capabilities');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM `role` r JOIN `permission` p
WHERE (r.code = 'USER' AND p.code = 'user')
   OR (r.code = 'ADMIN' AND p.code = 'system');

INSERT INTO user_role (user_id, role_id)
SELECT u.id, r.id FROM users u JOIN `role` r ON r.code = 'USER';
