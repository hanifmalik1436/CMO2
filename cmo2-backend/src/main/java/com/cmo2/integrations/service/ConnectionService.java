package com.cmo2.integrations.service;

import com.cmo2.integrations.core.ConnectionHealth;
import com.cmo2.integrations.core.Health;
import com.cmo2.integrations.core.Platform;
import com.cmo2.integrations.core.PlatformConnector;
import com.cmo2.integrations.core.PlatformCredentials;
import com.cmo2.integrations.domain.ConnectionStatus;
import com.cmo2.integrations.domain.PlatformConnection;
import com.cmo2.integrations.dto.IntegrationDtos.ConnectRequest;
import com.cmo2.integrations.dto.IntegrationDtos.ConnectionResponse;
import com.cmo2.integrations.dto.IntegrationDtos.ReconnectRequest;
import com.cmo2.integrations.gohighlevel.GhlAgencyService;
import com.cmo2.integrations.gohighlevel.GhlOAuthClient.TokenGrant;
import com.cmo2.integrations.repository.PlatformConnectionRepository;
import com.cmo2.shared.audit.AuditService;
import com.cmo2.shared.billing.PlanLimits;
import com.cmo2.shared.crypto.AesGcmService;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.tenancy.TenantGuard;
import com.cmo2.tenancy.repository.CompanyRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Manage platform connections: connect (encrypt + test), list (masked), re-test,
 * reconnect, disconnect. Authorization at the service layer; all reads tenant-scoped.
 */
@Service
public class ConnectionService {

    /** A GHL sub-account reached through the agency connection, with a cached ~24h location token. */
    public static final String AUTH_MODE_GHL_AGENCY = "GHL_AGENCY";
    private static final long TOKEN_REFRESH_MARGIN_SECONDS = 300;

    private final PlatformConnectionRepository connectionRepo;
    private final CompanyRepository companyRepo;
    private final ConnectorRegistry connectors;
    private final AesGcmService crypto;
    private final AuditService audit;
    private final PlanLimits planLimits;
    private final GhlAgencyService ghlAgency;

    public ConnectionService(PlatformConnectionRepository connectionRepo,
                             CompanyRepository companyRepo,
                             ConnectorRegistry connectors,
                             AesGcmService crypto,
                             AuditService audit,
                             PlanLimits planLimits,
                             GhlAgencyService ghlAgency) {
        this.connectionRepo = connectionRepo;
        this.companyRepo = companyRepo;
        this.connectors = connectors;
        this.crypto = crypto;
        this.audit = audit;
        this.planLimits = planLimits;
        this.ghlAgency = ghlAgency;
    }

    /** Connects a customer's GHL sub-account through the agency, seeded with a freshly minted location token. */
    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ConnectionResponse connectAgencyLocation(Long companyId, String locationId, TokenGrant locationToken) {
        TenantGuard.owned(companyRepo.findById(companyId))
                .orElseThrow(() -> ApiException.notFound("Company not found"));
        if (connectionRepo.existsByCompanyIdAndPlatform(companyId, Platform.GOHIGHLEVEL)) {
            throw ApiException.conflict("GoHighLevel is already connected for this customer");
        }
        planLimits.assertCanAddConnection();

        PlatformConnection conn = new PlatformConnection();
        conn.setCompanyId(companyId);
        conn.setPlatform(Platform.GOHIGHLEVEL);
        conn.setAuthMode(AUTH_MODE_GHL_AGENCY);
        conn.setLocationId(locationId);
        storeLocationToken(conn, locationToken);

        applyHealth(conn, connectors.require(Platform.GOHIGHLEVEL).testConnection(credentials(conn)));
        conn = connectionRepo.save(conn);
        audit.record("PLATFORM_CONNECTED", "PlatformConnection", String.valueOf(conn.getId()),
                "GoHighLevel sub-account " + locationId + " (agency) for company " + companyId);
        return ConnectionResponse.of(conn);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ConnectionResponse connect(Long companyId, ConnectRequest req) {
        TenantGuard.owned(companyRepo.findById(companyId))
                .orElseThrow(() -> ApiException.notFound("Company not found"));

        Platform platform = parsePlatform(req.platform());
        PlatformConnector connector = connectors.require(platform);

        if (connectionRepo.existsByCompanyIdAndPlatform(companyId, platform)) {
            throw ApiException.conflict(platform.displayName + " is already connected for this company");
        }
        planLimits.assertCanAddConnection();

        PlatformConnection conn = new PlatformConnection();
        conn.setCompanyId(companyId);
        conn.setPlatform(platform);
        conn.setApiKeyEnc(crypto.encrypt(req.apiKey()));
        conn.setApiKeyLast4(last4(req.apiKey()));
        conn.setLocationId(emptyToNull(req.locationId()));

        applyHealth(conn, connector.testConnection(credentials(conn)));
        conn = connectionRepo.save(conn);

        audit.record("PLATFORM_CONNECTED", "PlatformConnection", String.valueOf(conn.getId()),
                platform.displayName + " for company " + companyId);
        return ConnectionResponse.of(conn);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public List<ConnectionResponse> list(Long companyId) {
        return connectionRepo.findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
                .map(ConnectionResponse::of)
                .toList();
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST')")
    public ConnectionResponse test(Long connectionId) {
        PlatformConnection conn = findOwned(connectionId);
        applyHealth(conn, connectors.require(conn.getPlatform()).testConnection(credentials(conn)));
        return ConnectionResponse.of(connectionRepo.save(conn));
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ConnectionResponse reconnect(Long connectionId, ReconnectRequest req) {
        PlatformConnection conn = findOwned(connectionId);
        if (AUTH_MODE_GHL_AGENCY.equals(conn.getAuthMode())) {
            throw ApiException.badRequest("This sub-account is connected through your GHL agency; there is no key to rotate. Use Test to re-check it.");
        }
        conn.setApiKeyEnc(crypto.encrypt(req.apiKey()));
        conn.setApiKeyLast4(last4(req.apiKey()));
        if (req.locationId() != null) {
            conn.setLocationId(emptyToNull(req.locationId()));
        }
        applyHealth(conn, connectors.require(conn.getPlatform()).testConnection(credentials(conn)));
        conn = connectionRepo.save(conn);
        audit.record("PLATFORM_RECONNECTED", "PlatformConnection", String.valueOf(conn.getId()), null);
        return ConnectionResponse.of(conn);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public void disconnect(Long connectionId) {
        PlatformConnection conn = findOwned(connectionId);
        conn.setStatus(ConnectionStatus.DISCONNECTED);
        conn.setHealth(Health.RED);
        connectionRepo.save(conn);
        audit.record("PLATFORM_DISCONNECTED", "PlatformConnection", String.valueOf(conn.getId()), null);
    }

    // --- shared helpers (also used by SyncService) ----------------------------

    /**
     * Decrypts the stored key/tokens into usable credentials. Never logged. For agency-linked
     * GHL sub-accounts, re-mints the location token when it is within five minutes of expiry.
     */
    public PlatformCredentials credentials(PlatformConnection conn) {
        if (AUTH_MODE_GHL_AGENCY.equals(conn.getAuthMode())) {
            Instant expiresAt = conn.getOauthExpiresAt();
            boolean fresh = conn.getOauthAccessTokenEnc() != null && expiresAt != null
                    && expiresAt.isAfter(Instant.now().plusSeconds(TOKEN_REFRESH_MARGIN_SECONDS));
            if (!fresh) {
                storeLocationToken(conn, ghlAgency.mintLocationToken(conn.getLocationId()));
                connectionRepo.save(conn);
            }
            return PlatformCredentials.oauth(crypto.decrypt(conn.getOauthAccessTokenEnc()),
                    conn.getLocationId(), conn.getOauthExpiresAt());
        }
        if (!"OAUTH2".equals(conn.getAuthMode()) && !"CLIENT_CREDS".equals(conn.getAuthMode())) {
            return PlatformCredentials.staticKey(crypto.decrypt(conn.getApiKeyEnc()), conn.getLocationId());
        }
        String accessToken = conn.getOauthAccessTokenEnc() == null ? null : crypto.decrypt(conn.getOauthAccessTokenEnc());
        String refreshToken = conn.getOauthRefreshTokenEnc() == null ? null : crypto.decrypt(conn.getOauthRefreshTokenEnc());
        return new PlatformCredentials(null, conn.getLocationId(), accessToken, refreshToken, conn.getOauthExpiresAt(), null);
    }

    private void storeLocationToken(PlatformConnection conn, TokenGrant grant) {
        conn.setOauthAccessTokenEnc(crypto.encrypt(grant.accessToken()));
        conn.setOauthExpiresAt(grant.expiresAt());
        String scope = grant.scope();
        conn.setOauthScope(scope != null && scope.length() > 512 ? scope.substring(0, 512) : scope);
    }

    public void applyHealth(PlatformConnection conn, ConnectionHealth result) {
        conn.setHealth(result.status());
        if (result.status() == Health.RED) {
            conn.setStatus(ConnectionStatus.ERROR);
            conn.setLastError(result.message());
        } else {
            conn.setStatus(ConnectionStatus.CONNECTED);
            conn.setLastError(null);
        }
    }

    private PlatformConnection findOwned(Long connectionId) {
        return TenantGuard.owned(connectionRepo.findById(connectionId))
                .orElseThrow(() -> ApiException.notFound("Connection not found"));
    }

    private Platform parsePlatform(String raw) {
        try {
            return Platform.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown platform: " + raw);
        }
    }

    private String last4(String key) {
        if (key == null) return null;
        return key.length() <= 4 ? key : key.substring(key.length() - 4);
    }

    private String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
