-- =====================================================================
-- CMO2 :: V12 :: Customer profiles and daily KPI rollups
--
-- companies.timezone: the customer's own timezone (from its GHL
-- sub-account). Day boundaries for KPIs use it.
--
-- kpi_daily_rollups: one row per customer, platform, metric and local
-- day. open_value is the first reading of the day and metric_value the
-- latest, so daily figures (new leads, deals won) can be derived from
-- how much a running total grew, and one cycle can be compared with the
-- previous one without scanning every 15-minute snapshot.
--
-- customer_profiles: what CMO2 knows about each customer. The owner
-- edits offer, audience, banned topics and notes; the rest is copied
-- from GHL on every sync. Together with the company row it forms the
-- identity block given to every AI call for that customer.
-- =====================================================================

ALTER TABLE companies ADD COLUMN timezone VARCHAR(64) NULL AFTER website;

CREATE TABLE kpi_daily_rollups (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id        BIGINT       NOT NULL,
    company_id       BIGINT       NOT NULL,
    platform         VARCHAR(32)  NOT NULL,
    metric           VARCHAR(48)  NOT NULL,
    metric_date      DATE         NOT NULL,              -- the customer's local day
    open_value       DOUBLE       NOT NULL,              -- first reading that day
    metric_value     DOUBLE       NOT NULL,              -- latest reading that day
    samples          INT          NOT NULL DEFAULT 1,
    last_captured_at DATETIME     NOT NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rollup_day (company_id, platform, metric, metric_date),
    KEY idx_rollup_tenant_company_date (tenant_id, company_id, metric_date),
    CONSTRAINT fk_rollup_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE customer_profiles (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id         BIGINT        NOT NULL,
    company_id        BIGINT        NOT NULL,
    offer             VARCHAR(1000) NULL,                -- owner-written
    audience          VARCHAR(1000) NULL,                -- owner-written
    banned_topics     VARCHAR(1000) NULL,                -- owner-written
    notes             LONGTEXT      NULL,                -- owner-written
    ghl_business_name VARCHAR(160)  NULL,                -- from GHL, written by the customer
    city              VARCHAR(120)  NULL,
    country           VARCHAR(80)   NULL,
    phone             VARCHAR(40)   NULL,
    email             VARCHAR(160)  NULL,
    social_channels   VARCHAR(500)  NULL,                -- comma-separated, e.g. facebook,instagram
    facts_synced_at   DATETIME      NULL,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_customer_profile_company (company_id),
    KEY idx_customer_profile_tenant (tenant_id),
    CONSTRAINT fk_customer_profile_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
