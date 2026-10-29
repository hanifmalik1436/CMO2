-- =====================================================================
-- CMO2 :: V10 :: GoHighLevel agency connection + customer link
--
-- CMO² is the agency owner's console: the agency is the tenant, and each
-- customer is a company linked 1:1 to one GHL sub-account (location).
--
-- ghl_agency_connections holds the agency-level OAuth tokens (encrypted),
-- from which ~24h per-location tokens are minted via /oauth/locationToken.
-- One row per tenant; one GHL agency can belong to only one tenant.
--
-- companies.ghl_location_id links a customer to its sub-account. The unique
-- index means a sub-account can never be linked to two companies, in this
-- tenant or any other.
-- =====================================================================

CREATE TABLE ghl_agency_connections (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id         BIGINT        NOT NULL,
    ghl_company_id    VARCHAR(64)   NOT NULL,
    access_token_enc  LONGTEXT      NOT NULL,
    refresh_token_enc LONGTEXT      NOT NULL,
    expires_at        DATETIME      NOT NULL,
    scope             VARCHAR(2048) NULL,
    status            VARCHAR(24)   NOT NULL DEFAULT 'CONNECTED',
    last_error        VARCHAR(1000) NULL,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ghl_agency_tenant (tenant_id),
    UNIQUE KEY uk_ghl_agency_company (ghl_company_id),
    CONSTRAINT fk_ghl_agency_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE companies
    ADD COLUMN ghl_location_id VARCHAR(64) NULL AFTER website,
    ADD UNIQUE KEY uk_companies_ghl_location (ghl_location_id);
