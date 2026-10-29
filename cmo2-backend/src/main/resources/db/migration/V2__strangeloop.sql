-- =====================================================================
-- CMO2 :: V2 :: Strange Loop OS (Phase 2)
-- The 5-stage self-improving cycle. Stage transitions are persisted so a
-- crashed cycle resumes rather than restarts.
-- =====================================================================

CREATE TABLE loop_iterations (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT      NOT NULL,
    company_id    BIGINT      NOT NULL,
    iteration_no  INT         NOT NULL,
    status        VARCHAR(24) NOT NULL DEFAULT 'RUNNING',   -- RUNNING, COMPLETED, FAILED
    trigger_type  VARCHAR(24) NOT NULL DEFAULT 'MANUAL',    -- MANUAL, SCHEDULED, EVENT
    started_at    DATETIME    NULL,
    completed_at  DATETIME    NULL,
    insights_json LONGTEXT    NULL,                          -- Stage 2 output
    gaps_json     LONGTEXT    NULL,                          -- Stage 4 output
    error_message VARCHAR(1000) NULL,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_loop_iter_company_no (tenant_id, company_id, iteration_no),
    KEY idx_loop_iter_tenant (tenant_id),
    KEY idx_loop_iter_status (status),
    CONSTRAINT fk_loop_iter_company FOREIGN KEY (company_id) REFERENCES companies (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE loop_stage_runs (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT      NOT NULL,
    iteration_id BIGINT      NOT NULL,
    stage_no     INT         NOT NULL,                       -- 1..5
    stage_name   VARCHAR(32) NOT NULL,                       -- INNOVATION, LEARNING, ...
    status       VARCHAR(24) NOT NULL DEFAULT 'PENDING',     -- PENDING, RUNNING, COMPLETED, FAILED
    progress_pct INT         NOT NULL DEFAULT 0,
    data_json    LONGTEXT    NULL,
    started_at   DATETIME    NULL,
    completed_at DATETIME    NULL,
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_loop_stage_iter_no (iteration_id, stage_no),
    KEY idx_loop_stage_tenant (tenant_id),
    CONSTRAINT fk_loop_stage_iteration FOREIGN KEY (iteration_id) REFERENCES loop_iterations (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE loop_triggers (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT      NOT NULL,
    company_id      BIGINT      NOT NULL,
    trigger_type    VARCHAR(24) NOT NULL,                    -- SCHEDULE, CONDITION
    stage_no        INT         NULL,
    conditions_json LONGTEXT    NULL,
    actions_json    LONGTEXT    NULL,
    is_active       BIT(1)      NOT NULL DEFAULT b'1',
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_loop_trigger_company (tenant_id, company_id),
    CONSTRAINT fk_loop_trigger_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
