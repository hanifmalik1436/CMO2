package com.cmo2.integrations.oauth;

import com.cmo2.integrations.core.ConnectionHealth;
import com.cmo2.integrations.core.Platform;
import com.cmo2.integrations.core.PlatformConnector;
import com.cmo2.integrations.core.PlatformCredentials;
import com.cmo2.integrations.domain.ConnectionStatus;
import com.cmo2.integrations.domain.PlatformConnection;
import com.cmo2.integrations.oauth.OAuthProviderRegistry.ProviderSpec;
import com.cmo2.integrations.repository.PlatformConnectionRepository;
import com.cmo2.integrations.service.ConnectorRegistry;
import com.cmo2.shared.audit.AuditService;
import com.cmo2.shared.billing.PlanLimits;
import com.cmo2.shared.crypto.AesGcmService;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.shared.tenancy.TenantGuard;
import com.cmo2.tenancy.repository.CompanyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Generic OAuth2 authorization-code flow shared by every user-consent connector (LinkedIn,
 * Facebook, Instagram, Google Ads, TikTok) — one implementation, not five, matching the
 * "one interface, many connectors" pattern used for {@link PlatformConnector} itself.
 *
 * <p>PayPal's client-credentials flow (no browser redirect) uses {@link #clientCredentials}
 * directly from its connector and never touches {@link #buildAuthorizeUrl}/{@link #handleCallback}.
 */
@Service
public class OAuthService {

    private static final Logger log = LoggerFactory.getLogger(OAuthService.class);
    private static final java.time.Duration STATE_TTL = java.time.Duration.ofMinutes(10);

    private final OAuthStateRepository stateRepo;
    private final PlatformConnectionRepository connectionRepo;
    private final OAuthProviderRegistry providers;
    private final ConnectorRegistry connectors;
    private final AesGcmService crypto;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final CompanyRepository companyRepo;
    private final PlanLimits planLimits;

    public OAuthService(OAuthStateRepository stateRepo,
                        PlatformConnectionRepository connectionRepo,
                        OAuthProviderRegistry providers,
                        @Lazy ConnectorRegistry connectors,
                        AesGcmService crypto,
                        AuditService audit,
                        ObjectMapper objectMapper,
                        CompanyRepository companyRepo,
                        PlanLimits planLimits) {
        this.stateRepo = stateRepo;
        this.connectionRepo = connectionRepo;
        this.providers = providers;
        this.connectors = connectors;
        this.crypto = crypto;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.companyRepo = companyRepo;
        this.planLimits = planLimits;
    }

    /** Authenticated: starts a connect flow for the current tenant/company. */
    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public String buildAuthorizeUrl(Platform platform, Long companyId) {
        TenantGuard.owned(companyRepo.findById(companyId))
                .orElseThrow(() -> ApiException.notFound("Company not found"));
        ProviderSpec spec = providers.require(platform);
        if (!spec.configured()) {
            throw ApiException.badRequest(
                    platform.displayName + " OAuth is not configured on this server (missing client id/secret)");
        }
        if (!connectionRepo.existsByCompanyIdAndPlatform(companyId, platform)) {
            planLimits.assertCanAddConnection();
        }

        OAuthState s = new OAuthState();
        s.setTenantId(TenantContext.requireTenantId());
        s.setCompanyId(companyId);
        s.setPlatform(platform);
        s.setState(UUID.randomUUID().toString().replace("-", ""));
        s.setExpiresAt(Instant.now().plus(STATE_TTL));
        stateRepo.save(s);

        return spec.authorizeUrl()
                + "?response_type=code"
                + "&client_id=" + encode(spec.clientId())
                + "&redirect_uri=" + encode(providers.redirectUri(platform))
                + "&scope=" + encode(spec.scope())
                + "&state=" + encode(s.getState());
    }

    /** Unauthenticated: the provider redirects the user's browser here with no JWT. Tenant/company
     *  are re-derived from the persisted {@code state} row, never from any client-supplied value. */
    @Transactional
    public PlatformConnection handleCallback(Platform platform, String code, String state) {
        if (state == null || state.isBlank()) {
            throw ApiException.badRequest("Missing OAuth state");
        }
        OAuthState s = stateRepo.findByState(state)
                .orElseThrow(() -> ApiException.badRequest("Unknown or already-used OAuth state"));
        if (s.getPlatform() != platform) {
            throw ApiException.badRequest("OAuth state does not match platform");
        }
        if (s.getExpiresAt().isBefore(Instant.now())) {
            stateRepo.delete(s);
            throw ApiException.badRequest("OAuth state expired — please reconnect");
        }
        stateRepo.delete(s); // one-time use

        ProviderSpec spec = providers.require(platform);
        TokenResponse tokens = exchangeCode(spec, platform, code);

        PlatformConnection conn = connectionRepo
                .findByCompanyIdOrderByCreatedAtDesc(s.getCompanyId()).stream()
                .filter(c -> c.getPlatform() == platform)
                .findFirst()
                .orElseGet(() -> {
                    PlatformConnection c = new PlatformConnection();
                    c.setTenantId(s.getTenantId());
                    c.setCompanyId(s.getCompanyId());
                    c.setPlatform(platform);
                    return c;
                });

        conn.setAuthMode("OAUTH2");
        conn.setOauthAccessTokenEnc(crypto.encrypt(tokens.accessToken()));
        if (tokens.refreshToken() != null) {
            conn.setOauthRefreshTokenEnc(crypto.encrypt(tokens.refreshToken()));
        }
        conn.setOauthExpiresAt(tokens.expiresInSeconds() == null
                ? null : Instant.now().plusSeconds(tokens.expiresInSeconds()));
        conn.setApiKeyLast4(tokens.accessToken().length() <= 4
                ? tokens.accessToken() : tokens.accessToken().substring(tokens.accessToken().length() - 4));

        PlatformConnector connector = connectors.require(platform);
        ConnectionHealth health = connector.testConnection(credentials(conn));
        conn.setHealth(health.status());
        conn.setStatus(health.status() == com.cmo2.integrations.core.Health.RED
                ? ConnectionStatus.ERROR : ConnectionStatus.CONNECTED);
        conn.setLastError(health.status() == com.cmo2.integrations.core.Health.RED ? health.message() : null);

        conn = connectionRepo.save(conn);
        audit.recordFor(s.getTenantId(), null, "PLATFORM_OAUTH_CONNECTED", "PlatformConnection",
                String.valueOf(conn.getId()), platform.displayName + " for company " + s.getCompanyId());
        return conn;
    }

    /** Lazy refresh-on-use, called from ConnectionService.credentials() when near expiry. */
    @Transactional
    public PlatformConnection refresh(PlatformConnection conn) {
        ProviderSpec spec = providers.require(conn.getPlatform());
        if (conn.getOauthRefreshTokenEnc() == null) {
            log.warn("No refresh token stored for connection {} ({}); cannot refresh",
                    conn.getId(), conn.getPlatform());
            return conn;
        }
        String refreshToken = crypto.decrypt(conn.getOauthRefreshTokenEnc());

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("client_id", spec.clientId());
        form.add("client_secret", spec.clientSecret());

        try {
            String body = RestClient.create().post().uri(spec.tokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(String.class);
            TokenResponse tokens = parseTokenResponse(body);

            conn.setOauthAccessTokenEnc(crypto.encrypt(tokens.accessToken()));
            if (tokens.refreshToken() != null) {
                conn.setOauthRefreshTokenEnc(crypto.encrypt(tokens.refreshToken()));
            }
            conn.setOauthExpiresAt(tokens.expiresInSeconds() == null
                    ? null : Instant.now().plusSeconds(tokens.expiresInSeconds()));
            return connectionRepo.save(conn);
        } catch (Exception e) {
            log.warn("OAuth refresh failed for connection {} ({}): {}", conn.getId(), conn.getPlatform(), e.getMessage());
            conn.setHealth(com.cmo2.integrations.core.Health.RED);
            conn.setStatus(ConnectionStatus.ERROR);
            conn.setLastError("Token refresh failed: " + e.getMessage());
            return connectionRepo.save(conn);
        }
    }

    /** PayPal-style client-credentials token (no user consent, no stored refresh token — re-fetched
     *  and cached per call by the caller as needed). */
    public TokenResponse clientCredentials(String tokenUrl, String clientId, String clientSecret) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");

        String basic = java.util.Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        String body = RestClient.create().post().uri(tokenUrl)
                .header("Authorization", "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(String.class);
        return parseTokenResponse(body);
    }

    private TokenResponse exchangeCode(ProviderSpec spec, Platform platform, String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", providers.redirectUri(platform));
        form.add("client_id", spec.clientId());
        form.add("client_secret", spec.clientSecret());

        try {
            String body = RestClient.create().post().uri(spec.tokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(String.class);
            return parseTokenResponse(body);
        } catch (Exception e) {
            throw ApiException.badRequest(
                    "Could not complete " + platform.displayName + " connection: " + e.getMessage());
        }
    }

    private TokenResponse parseTokenResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String accessToken = text(root, "access_token");
            if (accessToken == null) {
                throw new IllegalStateException("Token response had no access_token");
            }
            String refreshToken = text(root, "refresh_token");
            Long expiresIn = root.hasNonNull("expires_in") ? root.get("expires_in").asLong() : null;
            return new TokenResponse(accessToken, refreshToken, expiresIn);
        } catch (Exception e) {
            throw new IllegalStateException("Unparseable OAuth token response: " + e.getMessage(), e);
        }
    }

    private String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private PlatformCredentials credentials(PlatformConnection conn) {
        return new PlatformCredentials(null, conn.getLocationId(),
                crypto.decrypt(conn.getOauthAccessTokenEnc()),
                conn.getOauthRefreshTokenEnc() == null ? null : crypto.decrypt(conn.getOauthRefreshTokenEnc()),
                conn.getOauthExpiresAt(), null);
    }

    private String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public record TokenResponse(String accessToken, String refreshToken, Long expiresInSeconds) {}
}
