-- =====================================================================
-- CMO2 :: V1 :: Identity, tenancy, RBAC, companies (Phase 0 + Phase 1 core)
-- Every tenant-owned table carries tenant_id with a composite index.
-- =====================================================================

CREATE TABLE tenants (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    name                VARCHAR(160) NOT NULL,
    status              VARCHAR(24)  NOT NULL DEFAULT 'TRIAL',   -- TRIAL, ACTIVE, PAST_DUE, SUSPENDED, CANCELED
    plan_code           VARCHAR(24)  NOT NULL DEFAULT 'STARTER', -- STARTER, PRO, ENTERPRISE
    stripe_customer_id  VARCHAR(64)  NULL,
    trial_ends_at       DATETIME     NULL,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE roles (
    id    BIGINT      NOT NULL AUTO_INCREMENT,
    name  VARCHAR(32) NOT NULL,    -- OWNER, ADMIN, STRATEGIST, ANALYST, VIEWER
    PRIMARY KEY (id),
    UNIQUE KEY uk_roles_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO roles (name) VALUES ('OWNER'), ('ADMIN'), ('STRATEGIST'), ('ANALYST'), ('VIEWER');

CREATE TABLE users (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id             BIGINT       NOT NULL,
    email                 VARCHAR(190) NOT NULL,
    password_hash         VARCHAR(100) NOT NULL,
    full_name             VARCHAR(160) NULL,
    status                VARCHAR(24)  NOT NULL DEFAULT 'PENDING_VERIFICATION', -- PENDING_VERIFICATION, ACTIVE, LOCKED, DISABLED
    email_verified        BIT(1)       NOT NULL DEFAULT b'0',
    mfa_secret_enc        VARCHAR(512) NULL,
    mfa_enabled           BIT(1)       NOT NULL DEFAULT b'0',
    failed_login_attempts INT          NOT NULL DEFAULT 0,
    locked_until          DATETIME     NULL,
    last_login_at         DATETIME     NULL,
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- Email is globally unique: one login identity belongs to exactly one tenant.
    -- This lets login resolve the user (and therefore the tenant) from email alone,
    -- with no tenant context required before authentication.
    UNIQUE KEY uk_users_email (email),
    KEY idx_users_tenant (tenant_id),
    CONSTRAINT fk_users_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Single-use, expiring tokens for email verification, password reset, and refresh rotation.
CREATE TABLE auth_tokens (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    token_hash  VARCHAR(100) NOT NULL,   -- SHA-256 of the opaque token; raw token never stored
    type        VARCHAR(32)  NOT NULL,   -- EMAIL_VERIFICATION, PASSWORD_RESET, REFRESH
    expires_at  DATETIME     NOT NULL,
    consumed_at DATETIME     NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_auth_tokens_hash (token_hash),
    KEY idx_auth_tokens_user (tenant_id, user_id, type),
    CONSTRAINT fk_auth_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE companies (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id          BIGINT       NOT NULL,
    name               VARCHAR(160) NOT NULL,
    industry           VARCHAR(120) NULL,
    brand_voice        VARCHAR(255) NULL,
    website            VARCHAR(255) NULL,
    status             VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE, ARCHIVED
    onboarding_step    INT          NOT NULL DEFAULT 0,           -- 0..5 wizard state
    brand_settings_json JSON        NULL,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_companies_tenant (tenant_id),
    UNIQUE KEY uk_companies_tenant_name (tenant_id, name),
    CONSTRAINT fk_companies_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Immutable audit trail (who/what/when/tenant). Append-only by convention.
CREATE TABLE audit_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT       NULL,
    actor_id    BIGINT       NULL,
    action      VARCHAR(80)  NOT NULL,
    entity      VARCHAR(80)  NULL,
    entity_id   VARCHAR(64)  NULL,
    detail      VARCHAR(1000) NULL,
    ip          VARCHAR(64)  NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_audit_tenant (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
