-- =====================================================================
-- CMO2 :: V17 :: Content Studio
--
-- content_sources: raw material the owner gives CMO2 for a customer
-- (a transcript, notes, an article). Ideas are extracted from it and
-- content is generated from it.
--
-- content_ideas: angles the strategy AI found in a source (insight,
-- story, tip...), each with hooks and a supporting quote. Re-analysing
-- a source replaces its ideas.
--
-- content_batches: one "generate" request: chosen ideas (and/or the
-- whole source) x chosen formats x drafts each. Runs in the background;
-- every piece becomes a draft in Approvals.
--
-- assets: which platform and format a draft was written for, and the
-- batch and idea it came from.
-- =====================================================================

CREATE TABLE content_sources (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL,
    company_id  BIGINT       NOT NULL,
    title       VARCHAR(200) NOT NULL,
    kind        VARCHAR(16)  NOT NULL,                  -- TRANSCRIPT, NOTES, ARTICLE
    body        LONGTEXT     NOT NULL,
    analyzed_at DATETIME     NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_content_source_company (tenant_id, company_id, created_at),
    CONSTRAINT fk_content_source_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE content_ideas (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT        NOT NULL,
    company_id  BIGINT        NOT NULL,
    source_id   BIGINT        NOT NULL,
    idea_type   VARCHAR(16)   NOT NULL,                 -- INSIGHT, STORY, TIP, CONTRARIAN, HOW_TO, STAT
    title       VARCHAR(300)  NOT NULL,
    summary     VARCHAR(1000) NULL,
    hooks_json  LONGTEXT      NULL,
    quote       VARCHAR(1000) NULL,
    position    INT           NOT NULL DEFAULT 0,
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_content_idea_source (tenant_id, source_id, position),
    CONSTRAINT fk_content_idea_source FOREIGN KEY (source_id) REFERENCES content_sources (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE content_batches (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT        NOT NULL,
    company_id      BIGINT        NOT NULL,
    source_id       BIGINT        NOT NULL,
    status          VARCHAR(16)   NOT NULL,             -- RUNNING, COMPLETED, PARTIAL, FAILED
    total_pieces    INT           NOT NULL,
    completed       INT           NOT NULL DEFAULT 0,
    failed          INT           NOT NULL DEFAULT 0,
    request_json    LONGTEXT      NULL,                 -- what was asked: ideas, formats, drafts each
    note            VARCHAR(1000) NULL,
    finished_at     DATETIME      NULL,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_content_batch_company (tenant_id, company_id, created_at),
    CONSTRAINT fk_content_batch_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE assets
    ADD COLUMN platform       VARCHAR(24) NULL AFTER language,     -- X, LINKEDIN, INSTAGRAM...
    ADD COLUMN content_format VARCHAR(40) NULL AFTER platform,     -- X_POST, LINKEDIN_CAROUSEL...
    ADD COLUMN batch_id       BIGINT      NULL AFTER content_format,
    ADD COLUMN idea_id        BIGINT      NULL AFTER batch_id,
    ADD KEY idx_assets_batch (batch_id);
