-- =====================================================================
-- CMO2 :: V3 :: Integrations + KPI ingestion (Phase 3)
-- Platform connections (encrypted creds), sync jobs, inbound webhooks, and the
-- kpi_snapshots that feed the dashboard.
-- =====================================================================

CREATE TABLE platform_connections (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id         BIGINT      NOT NULL,
    company_id        BIGINT      NOT NULL,
    platform          VARCHAR(32) NOT NULL,                  -- GOHIGHLEVEL, META, ...
    api_key_enc       LONGTEXT    NOT NULL,                  -- AES-256-GCM ciphertext; never returned to clients
    api_key_last4     VARCHAR(8)  NULL,                      -- for masked display (••••last4)
    location_id       VARCHAR(128) NULL,                     -- GHL sub-account / location
    webhook_secret_enc LONGTEXT   NULL,
    status            VARCHAR(24) NOT NULL DEFAULT 'CONNECTED', -- CONNECTED, DISCONNECTED, ERROR
    health            VARCHAR(16) NOT NULL DEFAULT 'YELLOW',     -- GREEN, YELLOW, RED
    last_sync_at      DATETIME    NULL,
    last_error        VARCHAR(1000) NULL,
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_conn_company_platform (tenant_id, company_id, platform),
    KEY idx_conn_tenant (tenant_id),
    KEY idx_conn_status (status),
    KEY idx_conn_location (location_id),
    CONSTRAINT fk_conn_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sync_jobs (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT      NOT NULL,
    connection_id BIGINT      NOT NULL,
    type          VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED',  -- SCHEDULED, MANUAL
    status        VARCHAR(16) NOT NULL DEFAULT 'RUNNING',    -- RUNNING, SUCCESS, FAILED
    kpis_ingested INT         NOT NULL DEFAULT 0,
    started_at    DATETIME    NULL,
    finished_at   DATETIME    NULL,
    error_message VARCHAR(1000) NULL,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_sync_tenant (tenant_id),
    KEY idx_sync_connection (connection_id, started_at),
    CONSTRAINT fk_sync_connection FOREIGN KEY (connection_id) REFERENCES platform_connections (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Raw inbound webhooks. tenant_id is nullable: it's resolved AFTER receipt (from the
-- payload's location), so this is a plain table (not tenant-filtered), like audit_log.
CREATE TABLE webhook_events (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT      NULL,
    source          VARCHAR(32) NOT NULL,                    -- GOHIGHLEVEL, STRIPE, ...
    event_type      VARCHAR(80) NULL,
    payload_json    LONGTEXT    NULL,
    signature_valid BIT(1)      NOT NULL DEFAULT b'0',
    processed       BIT(1)      NOT NULL DEFAULT b'0',
    processed_at    DATETIME    NULL,
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_webhook_source (source, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE kpi_snapshots (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT      NOT NULL,
    company_id  BIGINT      NOT NULL,
    platform    VARCHAR(32) NOT NULL,
    metric       VARCHAR(48) NOT NULL,                       -- leads, cpl, roas, ctr, conversion_rate, appointments
    metric_value DOUBLE      NOT NULL,
    captured_at DATETIME    NOT NULL,
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_kpi_lookup (tenant_id, company_id, metric, captured_at),
    KEY idx_kpi_company_time (company_id, captured_at),
    CONSTRAINT fk_kpi_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
