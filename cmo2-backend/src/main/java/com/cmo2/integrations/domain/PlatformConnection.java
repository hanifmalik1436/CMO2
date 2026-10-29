package com.cmo2.integrations.domain;

import com.cmo2.integrations.core.Health;
import com.cmo2.integrations.core.Platform;
import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A company's connection to one external platform. The API key is stored only as
 * AES-256-GCM ciphertext ({@code apiKeyEnc}); clients only ever see {@code ••••last4}.
 */
@Entity
@Table(name = "platform_connections")
@Getter
@Setter
public class PlatformConnection extends BaseTenantEntity {

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Platform platform;

    @Lob
    @Column(columnDefinition = "LONGTEXT", name = "api_key_enc")
    private String apiKeyEnc;

    @Column(name = "api_key_last4", length = 8)
    private String apiKeyLast4;

    @Column(name = "location_id", length = 128)
    private String locationId;

    /** API_KEY (static key/PAT), OAUTH2 (user-consent authorization code), or CLIENT_CREDS (PayPal-style). */
    @Column(name = "auth_mode", nullable = false, length = 16)
    private String authMode = "API_KEY";

    @Lob
    @Column(columnDefinition = "LONGTEXT", name = "oauth_access_token_enc")
    private String oauthAccessTokenEnc;

    @Lob
    @Column(columnDefinition = "LONGTEXT", name = "oauth_refresh_token_enc")
    private String oauthRefreshTokenEnc;

    @Column(name = "oauth_expires_at")
    private Instant oauthExpiresAt;

    @Lob
    @Column(columnDefinition = "LONGTEXT", name = "oauth_client_secret_enc")
    private String oauthClientSecretEnc;

    @Column(name = "oauth_scope", length = 512)
    private String oauthScope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ConnectionStatus status = ConnectionStatus.CONNECTED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Health health = Health.YELLOW;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;
}
