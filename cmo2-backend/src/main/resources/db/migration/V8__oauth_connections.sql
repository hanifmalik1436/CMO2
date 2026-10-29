-- =====================================================================
-- CMO2 :: V8 :: OAuth connections (Phase 8 follow-up: live connectors)
--
-- platform_connections previously only modeled a static API key. OAuth2
-- user-consent connectors (LinkedIn, Facebook, Instagram, Google Ads,
-- TikTok) and client-credentials connectors (PayPal) need token storage
-- instead. All new columns are nullable so existing static-key rows
-- (GoHighLevel, demo connectors) are unaffected; api_key_enc is relaxed
-- to nullable for pure-OAuth connections that never collect a key.
--
-- oauth_states carries the CSRF-nonce round trip between the authenticated
-- /oauth/authorize call and the unauthenticated provider callback: the
-- callback re-derives tenant/company from this row rather than from any
-- client-supplied value (NON-NEGOTIABLE RULE #1 — tenant_id never trusted
-- from client input).
-- =====================================================================

ALTER TABLE platform_connections
    MODIFY COLUMN api_key_enc LONGTEXT NULL,
    ADD COLUMN auth_mode              VARCHAR(16)  NOT NULL DEFAULT 'API_KEY' AFTER platform,
    ADD COLUMN oauth_access_token_enc LONGTEXT     NULL,
    ADD COLUMN oauth_refresh_token_enc LONGTEXT    NULL,
    ADD COLUMN oauth_expires_at       DATETIME     NULL,
    ADD COLUMN oauth_client_secret_enc LONGTEXT    NULL,
    ADD COLUMN oauth_scope            VARCHAR(512) NULL;

CREATE TABLE oauth_states (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL,
    company_id  BIGINT       NOT NULL,
    platform    VARCHAR(32)  NOT NULL,
    state       VARCHAR(128) NOT NULL,
    expires_at  DATETIME     NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_oauth_state (state),
    KEY idx_oauth_states_tenant (tenant_id),
    CONSTRAINT fk_oauth_states_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
