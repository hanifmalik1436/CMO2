-- =====================================================================
-- CMO2 :: V5 :: AI Strategist Council (Phase 5)
-- 4 phases of specialist agents, sessions, messages, and review gates RG-1..RG-4.
-- NOTE: the overview headlines "17 agents" but the Doc 05 catalog enumerates 19
-- (5 + 2 + 8 + 4). We seed the enumerated catalog; the count is reference data and
-- easy to trim if you prefer the headline 17.
-- =====================================================================

CREATE TABLE strategist_agents (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    code             VARCHAR(48) NOT NULL,
    name             VARCHAR(120) NOT NULL,
    phase            INT         NOT NULL,                    -- 1..4
    sort_order       INT         NOT NULL DEFAULT 0,
    deliverables_json LONGTEXT   NULL,                        -- JSON array of deliverable names
    system_prompt    LONGTEXT    NULL,
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_code (code),
    KEY idx_agent_phase (phase, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE strategist_sessions (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id         BIGINT      NOT NULL,
    company_id        BIGINT      NOT NULL,
    agent_code        VARCHAR(48) NOT NULL,
    status            VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE, COMPLETED, APPROVED, ARCHIVED
    context_json      LONGTEXT    NULL,
    deliverables_json LONGTEXT    NULL,
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_sess_company (tenant_id, company_id),
    CONSTRAINT fk_sess_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE strategist_messages (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT      NOT NULL,
    session_id   BIGINT      NOT NULL,
    role         VARCHAR(16) NOT NULL,                        -- USER, ASSISTANT, SYSTEM
    content      LONGTEXT    NULL,
    tokens_used  INT         NOT NULL DEFAULT 0,
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_msg_session (session_id, created_at),
    CONSTRAINT fk_msg_session FOREIGN KEY (session_id) REFERENCES strategist_sessions (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE review_gates (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT      NOT NULL,
    company_id  BIGINT      NOT NULL,
    gate_code   VARCHAR(8)  NOT NULL,                         -- RG-1 .. RG-4
    phase       INT         NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'PENDING',       -- PENDING, APPROVED
    approved_by BIGINT      NULL,
    approved_at DATETIME    NULL,
    notes       VARCHAR(1000) NULL,
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_gate_company_code (tenant_id, company_id, gate_code),
    KEY idx_gate_company (tenant_id, company_id),
    CONSTRAINT fk_gate_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Agent seed data lives in com.cmo2.strategists.domain.AgentSeeder (idempotent), so it
-- populates both Flyway-migrated MySQL and the H2 test schema from a single source.
