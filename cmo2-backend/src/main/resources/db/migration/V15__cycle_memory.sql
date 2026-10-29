-- =====================================================================
-- CMO2 :: V15 :: Per-customer cadence and cycle memory
--
-- loop_triggers: a customer's Strange Loop schedule (trigger_type
-- SCHEDULE): DAILY, WEEKLY or MONTHLY at a time of day in the
-- customer's own timezone. Quartz holds the matching trigger.
--
-- ai_interactions: every AI call, with its prompt and response, so each
-- cycle's record can show exactly what was asked and answered. Kept for
-- cmo2.ai.interaction-retention-days (prompts can contain lead data).
--
-- cycle_context: what happened in each cycle (decisions, tasks done and
-- pending, content produced, the owner's notes) and the compressed
-- context the next cycle starts from. CMO2's database is the source of
-- truth; Notion and Confluence pages are the readable mirror.
--
-- linear_task_links: the Linear issue CMO2 opened for each pending task,
-- so tasks are never duplicated and the owner's closes and comments can
-- be read back at the start of the next cycle.
-- =====================================================================

ALTER TABLE loop_triggers
    ADD COLUMN cadence      VARCHAR(16) NULL AFTER trigger_type,   -- DAILY, WEEKLY, MONTHLY
    ADD COLUMN time_of_day  VARCHAR(5)  NULL AFTER cadence,        -- HH:mm in the customer's timezone
    ADD COLUMN day_of_week  INT         NULL AFTER time_of_day,    -- 1 = Monday … 7 = Sunday (WEEKLY)
    ADD COLUMN day_of_month INT         NULL AFTER day_of_week;    -- 1 … 28 (MONTHLY)

CREATE TABLE ai_interactions (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT      NOT NULL,
    company_id    BIGINT      NULL,
    iteration_id  BIGINT      NULL,
    stage         VARCHAR(24) NULL,
    kind          VARCHAR(64) NOT NULL,                 -- asset type, TRANSLATION, STRAT:<agent>
    tier          VARCHAR(16) NOT NULL,
    model         VARCHAR(80) NULL,
    prompt_text   LONGTEXT    NULL,
    response_text LONGTEXT    NULL,
    input_tokens  BIGINT      NOT NULL DEFAULT 0,
    output_tokens BIGINT      NOT NULL DEFAULT 0,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ai_company_time (tenant_id, company_id, created_at),
    KEY idx_ai_created (created_at),
    CONSTRAINT fk_ai_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cycle_context (
    id                    BIGINT   NOT NULL AUTO_INCREMENT,
    tenant_id             BIGINT   NOT NULL,
    company_id            BIGINT   NOT NULL,
    iteration_id          BIGINT   NOT NULL,
    iteration_no          INT      NOT NULL,
    period_start          DATETIME NOT NULL,
    period_end            DATETIME NOT NULL,
    decisions_json        LONGTEXT NULL,
    tasks_completed_json  LONGTEXT NULL,
    tasks_pending_json    LONGTEXT NULL,
    content_produced_json LONGTEXT NULL,
    operator_notes_json   LONGTEXT NULL,
    summary               LONGTEXT NULL,
    compressed_context    LONGTEXT NULL,
    published_refs_json   LONGTEXT NULL,                 -- tool → {pageId, url}
    published_body        LONGTEXT NULL,                 -- exactly what CMO2 wrote, to spot the owner's edits
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cycle_context_iteration (iteration_id),
    KEY idx_cycle_context_company (tenant_id, company_id, period_end),
    CONSTRAINT fk_cycle_context_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE linear_task_links (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT       NOT NULL,
    company_id      BIGINT       NOT NULL,
    task_key        VARCHAR(120) NOT NULL,              -- e.g. asset:42:approve
    title           VARCHAR(255) NOT NULL,
    linear_issue_id VARCHAR(64)  NOT NULL,
    identifier      VARCHAR(32)  NULL,                  -- e.g. CMO-12
    url             VARCHAR(512) NULL,
    state_type      VARCHAR(24)  NULL,                  -- Linear workflow state type: unstarted, started, completed, canceled…
    closed_by_cmo2  BIT(1)       NOT NULL DEFAULT b'0',
    last_read_at    DATETIME     NULL,                  -- comments after this are new operator notes
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_linear_task (company_id, task_key),
    KEY idx_linear_task_tenant (tenant_id, company_id),
    CONSTRAINT fk_linear_task_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
