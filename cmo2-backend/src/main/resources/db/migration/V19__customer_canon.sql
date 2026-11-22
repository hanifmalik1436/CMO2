-- =====================================================================
-- V19: each customer's brand canon, and prompt-cache accounting.
--
-- customer_canon_versions: the owner-approved description of the business
-- and its brand rules (docs/plans/2026-11-19-drift-control-canon-lanes-graph.md,
-- step 1). It goes, in full, at the start of every AI call for the customer,
-- so a long history can never outweigh who the customer is. At most one
-- DRAFT and one APPROVED version per customer; older approved versions are
-- RETIRED and kept as history. version_no is given on approval.
--
-- ai_interactions: tokens read from and written to the provider's prompt
-- cache, and budget_tokens, the figure the monthly budgets add up (cache
-- reads count a tenth: they cost a tenth of normal input or less).
-- =====================================================================

CREATE TABLE customer_canon_versions (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id           BIGINT        NOT NULL,
    company_id          BIGINT        NOT NULL,
    version_no          INT           NULL,
    status              VARCHAR(16)   NOT NULL,                  -- DRAFT, APPROVED, RETIRED
    content             LONGTEXT      NOT NULL,
    change_note         VARCHAR(500)  NULL,
    proposed_by_user_id BIGINT        NULL,
    approved_by_user_id BIGINT        NULL,
    approved_at         DATETIME      NULL,
    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_canon_version (company_id, version_no),
    KEY idx_canon_company_status (tenant_id, company_id, status),
    CONSTRAINT fk_canon_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE ai_interactions
    ADD COLUMN cache_read_tokens  BIGINT NOT NULL DEFAULT 0 AFTER output_tokens,
    ADD COLUMN cache_write_tokens BIGINT NOT NULL DEFAULT 0 AFTER cache_read_tokens,
    ADD COLUMN budget_tokens      BIGINT NOT NULL DEFAULT 0 AFTER cache_write_tokens;

UPDATE ai_interactions SET budget_tokens = input_tokens + output_tokens;
