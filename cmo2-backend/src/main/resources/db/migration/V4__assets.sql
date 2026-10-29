-- =====================================================================
-- CMO2 :: V4 :: AI Asset Generator + token accounting (Phase 4)
-- =====================================================================

-- Per-tenant AI token usage, rolled up by billing period (YYYY-MM). Feeds CostGuard.
CREATE TABLE usage_records (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT      NOT NULL,
    metric      VARCHAR(48) NOT NULL,                        -- e.g. ai_tokens
    quantity    BIGINT      NOT NULL DEFAULT 0,
    period      VARCHAR(7)  NOT NULL,                        -- YYYY-MM
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_usage_lookup (tenant_id, metric, period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Global template reference data (not tenant-owned) for the type picker.
CREATE TABLE asset_templates (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    type          VARCHAR(32) NOT NULL,                      -- EMAIL_SEQUENCE, EBOOK, ...
    language      VARCHAR(8)  NOT NULL DEFAULT 'EN',
    name          VARCHAR(160) NOT NULL,
    body_template LONGTEXT    NULL,
    variables_json LONGTEXT   NULL,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tmpl_type (type, language)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Generated assets, versioned and bilingual. content_json holds the flexible AI payload.
CREATE TABLE assets (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT      NOT NULL,
    company_id      BIGINT      NOT NULL,
    type            VARCHAR(32) NOT NULL,
    language        VARCHAR(8)  NOT NULL DEFAULT 'EN',        -- EN, ES
    status          VARCHAR(24) NOT NULL DEFAULT 'GENERATED', -- GENERATED, TRANSLATED, DEPLOYED, ARCHIVED
    title           VARCHAR(255) NULL,
    content_json    LONGTEXT    NULL,
    version         INT         NOT NULL DEFAULT 1,
    parent_asset_id BIGINT      NULL,                         -- links versions / EN→ES
    tokens_used     INT         NOT NULL DEFAULT 0,
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_assets_company (tenant_id, company_id, type),
    KEY idx_assets_parent (parent_asset_id),
    CONSTRAINT fk_assets_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE asset_deployments (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT      NOT NULL,
    asset_id      BIGINT      NOT NULL,
    platform      VARCHAR(32) NOT NULL,
    status        VARCHAR(24) NOT NULL DEFAULT 'PENDING',     -- ACCEPTED, FAILED, PENDING
    external_ref  VARCHAR(255) NULL,
    error_message VARCHAR(1000) NULL,
    deployed_at   DATETIME    NULL,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_deploy_asset (asset_id),
    KEY idx_deploy_tenant (tenant_id),
    CONSTRAINT fk_deploy_asset FOREIGN KEY (asset_id) REFERENCES assets (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Seed a template name per asset type so the UI picker has options.
INSERT INTO asset_templates (type, language, name) VALUES
  ('EMAIL_SEQUENCE', 'EN', 'Welcome Nurture (7 emails)'),
  ('EBOOK',          'EN', 'Lead-Magnet eBook'),
  ('BLOG_POST',      'EN', 'SEO Blog Post'),
  ('SOCIAL_MEDIA',   'EN', 'Social Pack (5 posts)'),
  ('LANDING_PAGE',   'EN', 'High-Converting Landing Page'),
  ('SMS_CAMPAIGN',   'EN', 'SMS Drip (5 messages)'),
  ('CHATBOT_SCRIPT', 'EN', 'Chatbot Qualifier Flow');
