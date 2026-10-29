-- =====================================================================
-- CMO2 :: V6 :: Alerts + Reports (Phase 6)
-- (kpi_snapshots already exists from V3; analytics aggregates over it.)
-- =====================================================================

CREATE TABLE alert_rules (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT      NOT NULL,
    company_id    BIGINT      NOT NULL,
    metric        VARCHAR(48) NOT NULL,
    operator      VARCHAR(8)  NOT NULL,                       -- GT, LT, GTE, LTE
    threshold     DOUBLE      NOT NULL,
    severity      VARCHAR(16) NOT NULL DEFAULT 'WARNING',     -- INFO, WARNING, CRITICAL
    channels_json LONGTEXT    NULL,                           -- [{"type":"EMAIL","target":"..."}, ...]
    active        BIT(1)      NOT NULL DEFAULT b'1',
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_rule_company (tenant_id, company_id),
    KEY idx_rule_active (active),
    CONSTRAINT fk_rule_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE alerts (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT      NOT NULL,
    company_id      BIGINT      NOT NULL,
    rule_id         BIGINT      NULL,
    metric          VARCHAR(48) NOT NULL,
    metric_value    DOUBLE      NOT NULL,
    severity        VARCHAR(16) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'FIRED',     -- FIRED, ACKED, RESOLVED
    message         VARCHAR(1000) NULL,
    dispatched_json LONGTEXT    NULL,                          -- channels actually delivered to
    fired_at        DATETIME    NULL,
    acked_by        BIGINT      NULL,
    acked_at        DATETIME    NULL,
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_alert_tenant (tenant_id, status, fired_at),
    KEY idx_alert_rule (rule_id, status),
    CONSTRAINT fk_alert_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE reports (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT      NOT NULL,
    company_id     BIGINT      NULL,                           -- null = portfolio report
    type           VARCHAR(16) NOT NULL DEFAULT 'ON_DEMAND',   -- WEEKLY, ON_DEMAND
    title          VARCHAR(255) NOT NULL,
    period         VARCHAR(32) NULL,
    format         VARCHAR(8)  NOT NULL DEFAULT 'PDF',
    status         VARCHAR(16) NOT NULL DEFAULT 'GENERATED',
    content_base64 LONGTEXT    NULL,                           -- PDF bytes, base64
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_report_tenant (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
