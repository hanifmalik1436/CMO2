-- =====================================================================
-- CMO2 :: V16 :: Learning records and experiments
--
-- learning_records: Stage 2's reading of each cycle. facts_json is the
-- week-over-week comparison computed in Java; observations and
-- hypotheses are the AI's (or, without AI, rule-based) reading of
-- those facts only.
--
-- experiments: Stage 5's proposals, each a hypothesis tied to one
-- action from CMO2's fixed list (draft a social post, change an
-- existing GHL custom value, or a suggestion for the owner). Nothing
-- client-facing runs without the owner's approval. An applied
-- experiment is measured in a later cycle against its baseline, and
-- the result feeds the next plan.
-- =====================================================================

CREATE TABLE learning_records (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id         BIGINT        NOT NULL,
    company_id        BIGINT        NOT NULL,
    iteration_id      BIGINT        NOT NULL,
    iteration_no      INT           NOT NULL,
    source            VARCHAR(24)   NOT NULL,             -- ai, rules, no-data
    facts_json        LONGTEXT      NULL,
    observations_json LONGTEXT      NULL,
    hypotheses_json   LONGTEXT      NULL,
    confidence        DOUBLE        NOT NULL DEFAULT 0,
    note              VARCHAR(1000) NULL,                 -- why AI wasn't used, if it wasn't
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_iteration (iteration_id),
    KEY idx_learning_company (tenant_id, company_id, created_at),
    CONSTRAINT fk_learning_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE experiments (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id          BIGINT        NOT NULL,
    company_id         BIGINT        NOT NULL,
    iteration_id       BIGINT        NOT NULL,              -- the cycle that proposed it
    hypothesis         VARCHAR(500)  NOT NULL,
    action_type        VARCHAR(32)   NOT NULL,              -- DRAFT_SOCIAL_POST, UPDATE_CUSTOM_VALUE, SUGGESTION
    capability         VARCHAR(32)   NULL,                  -- the GHL capability it uses, if any
    risk_tier          VARCHAR(24)   NOT NULL,
    params_json        LONGTEXT      NULL,
    status             VARCHAR(16)   NOT NULL,              -- PROPOSED, APPLIED, REJECTED, FAILED, MEASURED
    metric             VARCHAR(48)   NOT NULL,
    expected_direction VARCHAR(8)    NOT NULL,              -- up, down
    baseline_value     DOUBLE        NULL,
    result_value       DOUBLE        NULL,
    outcome            VARCHAR(16)   NULL,                  -- SUPPORTED, NOT_SUPPORTED, INCONCLUSIVE
    result_summary     VARCHAR(1000) NULL,
    asset_id           BIGINT        NULL,                  -- the draft, for DRAFT_SOCIAL_POST
    decided_by         BIGINT        NULL,
    decided_at         DATETIME      NULL,
    decision_note      VARCHAR(1000) NULL,
    applied_at         DATETIME      NULL,
    measured_at        DATETIME      NULL,
    created_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_experiment_company (tenant_id, company_id, status),
    KEY idx_experiment_asset (asset_id),
    CONSTRAINT fk_experiment_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
