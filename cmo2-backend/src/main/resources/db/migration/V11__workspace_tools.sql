-- =====================================================================
-- CMO2 :: V11 :: Workspace tools (Notion / Confluence / Linear / Slack)
--
-- tenant_doc_connections: the agency's own connection to each tool. The
-- secret (API token or Slack webhook URL) is AES-256-GCM ciphertext;
-- config_json holds the non-secret ids (root page, space, team).
--
-- company_doc_targets: where each customer's records live inside those
-- tools (its Notion page, Confluence page and Linear project).
-- =====================================================================

CREATE TABLE tenant_doc_connections (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT        NOT NULL,
    provider        VARCHAR(16)   NOT NULL,                 -- NOTION, CONFLUENCE, LINEAR, SLACK
    secret_enc      LONGTEXT      NOT NULL,
    secret_last4    VARCHAR(8)    NULL,
    config_json     LONGTEXT      NULL,
    status          VARCHAR(16)   NOT NULL DEFAULT 'ERROR', -- CONNECTED, ERROR
    last_error      VARCHAR(1000) NULL,
    last_checked_at DATETIME      NULL,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_conn_tenant_provider (tenant_id, provider),
    CONSTRAINT fk_doc_conn_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE company_doc_targets (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id          BIGINT        NOT NULL,
    company_id         BIGINT        NOT NULL,
    notion_page_id     VARCHAR(64)   NULL,
    notion_url         VARCHAR(512)  NULL,
    confluence_page_id VARCHAR(64)   NULL,
    confluence_url     VARCHAR(512)  NULL,
    linear_project_id  VARCHAR(64)   NULL,
    linear_url         VARCHAR(512)  NULL,
    last_error         VARCHAR(1000) NULL,
    created_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_targets_company (company_id),
    KEY idx_doc_targets_tenant (tenant_id),
    CONSTRAINT fk_doc_targets_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
