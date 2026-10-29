package com.cmo2.integrations.gohighlevel;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * The tenant's (agency's) connection to its GoHighLevel agency account. Tokens are stored only
 * as AES-256-GCM ciphertext. One per tenant, and one GHL agency can link to only one tenant.
 */
@Entity
@Table(name = "ghl_agency_connections", uniqueConstraints = {
        @UniqueConstraint(name = "uk_ghl_agency_tenant", columnNames = "tenant_id"),
        @UniqueConstraint(name = "uk_ghl_agency_company", columnNames = "ghl_company_id")})
@Getter
@Setter
public class GhlAgencyConnection extends BaseTenantEntity {

    public static final String STATUS_CONNECTED = "CONNECTED";
    public static final String STATUS_ERROR = "ERROR";

    @Column(name = "ghl_company_id", nullable = false, length = 64)
    private String ghlCompanyId;

    @Lob
    @Column(columnDefinition = "LONGTEXT", name = "access_token_enc", nullable = false)
    private String accessTokenEnc;

    @Lob
    @Column(columnDefinition = "LONGTEXT", name = "refresh_token_enc", nullable = false)
    private String refreshTokenEnc;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "scope", length = 2048)
    private String scope;

    @Column(nullable = false, length = 24)
    private String status = STATUS_CONNECTED;

    @Column(name = "last_error", length = 1000)
    private String lastError;
}
