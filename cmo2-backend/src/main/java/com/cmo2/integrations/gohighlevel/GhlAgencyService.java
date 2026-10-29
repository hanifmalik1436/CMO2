package com.cmo2.integrations.gohighlevel;

import com.cmo2.integrations.events.AgencyConnectionFailedEvent;
import com.cmo2.integrations.gohighlevel.GhlOAuthClient.TokenGrant;
import com.cmo2.shared.audit.AuditService;
import com.cmo2.shared.crypto.AesGcmService;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The tenant's link to its GoHighLevel agency: connect (OAuth code exchange), keep the agency
 * token fresh, mint per-location tokens, and list the agency's sub-accounts.
 *
 * <p>Only the controller-facing methods carry {@code @PreAuthorize}. The token methods are also
 * called by the background sync, which runs with a tenant bound but no logged-in user.
 */
@Service
public class GhlAgencyService {

    private static final Logger log = LoggerFactory.getLogger(GhlAgencyService.class);
    private static final long REFRESH_MARGIN_SECONDS = 300;
    private static final int LOCATIONS_PAGE_SIZE = 100;
    private static final int MAX_LOCATION_PAGES = 50;

    private final GhlAgencyConnectionRepository repo;
    private final GhlOAuthClient oauth;
    private final GhlGateway gateway;
    private final AesGcmService crypto;
    private final AuditService audit;
    private final TransactionTemplate newTransaction;
    private final String installUrl;
    private final String expectedAgencyCompanyId;
    private final ApplicationEventPublisher events;
    private final Map<Long, ReentrantLock> refreshLocks = new ConcurrentHashMap<>();

    public GhlAgencyService(GhlAgencyConnectionRepository repo,
                            GhlOAuthClient oauth,
                            GhlGateway gateway,
                            AesGcmService crypto,
                            AuditService audit,
                            PlatformTransactionManager txManager,
                            ApplicationEventPublisher events,
                            @Value("${cmo2.ghl.oauth.install-url:}") String installUrl,
                            @Value("${cmo2.ghl.agency-company-id:}") String expectedAgencyCompanyId) {
        this.repo = repo;
        this.oauth = oauth;
        this.gateway = gateway;
        this.crypto = crypto;
        this.audit = audit;
        this.events = events;
        this.newTransaction = new TransactionTemplate(txManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.installUrl = installUrl;
        this.expectedAgencyCompanyId = expectedAgencyCompanyId;
    }

    public record AgencyStatus(boolean appConfigured, String installUrl, boolean connected,
                               String ghlCompanyId, String status, Instant tokenExpiresAt,
                               String scope, String lastError) {}

    /** A GHL sub-account as listed by the agency. */
    public record GhlLocation(String id, String name, String email, String phone, String website,
                              String timezone, String city, String country) {}

    // ---- controller-facing (role-checked) --------------------------------------------

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public AgencyStatus status() {
        boolean configured = oauth.isConfigured() && notBlank(installUrl);
        return repo.findFirstByOrderByIdAsc()
                .map(c -> new AgencyStatus(configured, configured ? installUrl : null, true, c.getGhlCompanyId(),
                        c.getStatus(), c.getExpiresAt(), c.getScope(), c.getLastError()))
                .orElse(new AgencyStatus(configured, configured ? installUrl : null, false,
                        null, null, null, null, null));
    }

    /** Completes the install: exchanges the code GHL sent back for agency tokens and stores them. */
    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public AgencyStatus connect(String code) {
        if (code == null || code.isBlank()) {
            throw ApiException.badRequest("Missing authorization code");
        }
        TokenGrant grant;
        try {
            grant = oauth.exchangeAgencyCode(code.trim());
        } catch (GhlApiException e) {
            throw ApiException.badRequest("GoHighLevel rejected the connection: " + e.getMessage());
        }
        if (grant.userType() != null && !"Company".equalsIgnoreCase(grant.userType())) {
            throw ApiException.badRequest("That install is for a single sub-account. Install the app on your agency account instead.");
        }
        if (!notBlank(grant.companyId()) || !notBlank(grant.refreshToken())) {
            throw ApiException.badRequest("GoHighLevel did not return an agency company id and refresh token");
        }
        if (notBlank(expectedAgencyCompanyId) && !expectedAgencyCompanyId.equals(grant.companyId())) {
            throw ApiException.forbidden("This GHL agency (" + grant.companyId()
                    + ") is not the one configured for CMO² (CMO2_GHL_AGENCY_COMPANY_ID)");
        }

        GhlAgencyConnection conn = repo.findFirstByOrderByIdAsc().orElseGet(GhlAgencyConnection::new);
        conn.setGhlCompanyId(grant.companyId());
        applyGrant(conn, grant);
        try {
            conn = repo.saveAndFlush(conn);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("This GHL agency is already connected to another CMO² account");
        }
        audit.record("GHL_AGENCY_CONNECTED", "GhlAgencyConnection", String.valueOf(conn.getId()), grant.companyId());
        return status();
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public void disconnect() {
        repo.findFirstByOrderByIdAsc().ifPresent(conn -> {
            repo.delete(conn);
            audit.record("GHL_AGENCY_DISCONNECTED", "GhlAgencyConnection", String.valueOf(conn.getId()), conn.getGhlCompanyId());
        });
    }

    /** Every sub-account under the connected agency, paged through GHL's location search. */
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public List<GhlLocation> listLocations() {
        String companyId = requireConnection().getGhlCompanyId();
        String token = agencyAccessToken();
        List<GhlLocation> out = new ArrayList<>();
        for (int page = 0; page < MAX_LOCATION_PAGES; page++) {
            JsonNode body = gateway.get("/locations/search",
                    Map.of("companyId", companyId, "skip", page * LOCATIONS_PAGE_SIZE, "limit", LOCATIONS_PAGE_SIZE),
                    token, companyId);
            JsonNode locations = body == null ? null : body.get("locations");
            if (locations == null || !locations.isArray() || locations.isEmpty()) {
                break;
            }
            locations.forEach(l -> out.add(toLocation(l)));
            if (locations.size() < LOCATIONS_PAGE_SIZE) {
                break;
            }
        }
        return out;
    }

    // ---- internal (no role check: also used by the background sync) -------------------

    public boolean isConnected() {
        return repo.findFirstByOrderByIdAsc().isPresent();
    }

    public String ghlCompanyId() {
        return requireConnection().getGhlCompanyId();
    }

    /** A valid agency access token, refreshing it first if it expires within five minutes. */
    public String agencyAccessToken() {
        GhlAgencyConnection conn = requireConnection();
        if (conn.getExpiresAt().isAfter(Instant.now().plusSeconds(REFRESH_MARGIN_SECONDS))) {
            return crypto.decrypt(conn.getAccessTokenEnc());
        }
        return refreshAgencyToken();
    }

    /**
     * Mints a location token. Succeeds only for sub-accounts inside this agency, which is how
     * CMO² proves a location belongs to you before linking it to a customer.
     */
    public TokenGrant mintLocationToken(String locationId) {
        String companyId = ghlCompanyId();
        try {
            return oauth.mintLocationToken(agencyAccessToken(), companyId, locationId);
        } catch (GhlApiException e) {
            if (e.isUnauthorized()) {
                return oauth.mintLocationToken(refreshAgencyToken(), companyId, locationId);
            }
            throw e;
        }
    }

    /** Reads one sub-account's profile with its location token. */
    public GhlLocation getLocation(String locationId, String locationToken) {
        JsonNode body = gateway.get("/locations/" + locationId, Map.of(), locationToken, locationId);
        JsonNode location = body == null ? null : (body.has("location") ? body.get("location") : body);
        if (location == null || location.isNull()) {
            throw new GhlApiException(404, "GHL returned no profile for location " + locationId);
        }
        return toLocation(location);
    }

    /**
     * Single-use refresh tokens mean two concurrent refreshes would lock CMO² out of GHL, so
     * refreshes are serialised per tenant and run in their own transaction: the new refresh
     * token is committed before anything else can fail.
     */
    String refreshAgencyToken() {
        Long tenantId = TenantContext.requireTenantId();
        ReentrantLock lock = refreshLocks.computeIfAbsent(tenantId, id -> new ReentrantLock());
        lock.lock();
        try {
            return newTransaction.execute(status -> {
                GhlAgencyConnection conn = requireConnection();
                if (conn.getExpiresAt().isAfter(Instant.now().plusSeconds(REFRESH_MARGIN_SECONDS))) {
                    return crypto.decrypt(conn.getAccessTokenEnc()); // another thread refreshed first
                }
                TokenGrant grant = oauth.refreshAgency(crypto.decrypt(conn.getRefreshTokenEnc()));
                applyGrant(conn, grant);
                repo.save(conn);
                return grant.accessToken();
            });
        } catch (GhlApiException e) {
            log.warn("GHL agency token refresh failed for tenant {}: {}", tenantId, e.getMessage());
            newTransaction.executeWithoutResult(status -> repo.findFirstByOrderByIdAsc().ifPresent(conn -> {
                conn.setStatus(GhlAgencyConnection.STATUS_ERROR);
                conn.setLastError("Token refresh failed — reconnect your GHL agency: " + e.getMessage());
                repo.save(conn);
            }));
            events.publishEvent(new AgencyConnectionFailedEvent(tenantId, e.getMessage()));
            throw new GhlApiException(401, "GHL agency token could not be refreshed; reconnect the agency in CMO²");
        } finally {
            lock.unlock();
        }
    }

    private void applyGrant(GhlAgencyConnection conn, TokenGrant grant) {
        conn.setAccessTokenEnc(crypto.encrypt(grant.accessToken()));
        if (notBlank(grant.refreshToken())) {
            conn.setRefreshTokenEnc(crypto.encrypt(grant.refreshToken()));
        }
        conn.setExpiresAt(grant.expiresAt());
        if (notBlank(grant.scope())) {
            conn.setScope(grant.scope());
        }
        conn.setStatus(GhlAgencyConnection.STATUS_CONNECTED);
        conn.setLastError(null);
    }

    private GhlAgencyConnection requireConnection() {
        return repo.findFirstByOrderByIdAsc()
                .orElseThrow(() -> ApiException.badRequest("Connect your GoHighLevel agency first"));
    }

    private static GhlLocation toLocation(JsonNode l) {
        return new GhlLocation(text(l, "id"), text(l, "name"), text(l, "email"), text(l, "phone"),
                text(l, "website"), text(l, "timezone"), text(l, "city"), text(l, "country"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
