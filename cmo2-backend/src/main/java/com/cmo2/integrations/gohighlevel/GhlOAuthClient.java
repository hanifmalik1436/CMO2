package com.cmo2.integrations.gohighlevel;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;

/**
 * GoHighLevel's OAuth token endpoints. Kept apart from {@link GhlGateway} because these calls
 * mint the tokens the gateway then uses.
 *
 * <p>GHL's own docs disagree on the location-token call (the guide shows
 * {@code /oauth/locationToken}, JSON, {@code Version: 2021-07-28}; the v3 reference shows
 * {@code /oauth/location-token}, form-encoded, {@code Version: v3}). The constants below are
 * the single place to adjust once this is exercised against a live Marketplace app.
 */
@Component
public class GhlOAuthClient {

    static final String TOKEN_PATH = "/oauth/token";
    static final String LOCATION_TOKEN_PATH = "/oauth/locationToken";
    static final String LOCATION_TOKEN_VERSION = "2021-07-28";

    private final RestClient client;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    @Autowired
    public GhlOAuthClient(
            @Value("${cmo2.integrations.gohighlevel.base-url:https://services.leadconnectorhq.com}") String baseUrl,
            @Value("${cmo2.ghl.oauth.client-id:}") String clientId,
            @Value("${cmo2.ghl.oauth.client-secret:}") String clientSecret,
            @Value("${cmo2.ghl.oauth.redirect-uri:}") String redirectUri) {
        this(RestClient.builder().baseUrl(baseUrl), clientId, clientSecret, redirectUri);
    }

    GhlOAuthClient(RestClient.Builder builder, String clientId, String clientSecret, String redirectUri) {
        this.client = builder.build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    /** Tokens GHL issued. {@code companyId}/{@code locationId}/{@code userType} are null when GHL omits them. */
    public record TokenGrant(String accessToken, String refreshToken, Instant expiresAt, String scope,
                             String userType, String companyId, String locationId) {}

    public boolean isConfigured() {
        return notBlank(clientId) && notBlank(clientSecret);
    }

    /** Exchanges the authorization code from the install redirect for agency (Company) tokens. */
    public TokenGrant exchangeAgencyCode(String code) {
        MultiValueMap<String, String> form = baseForm();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("user_type", "Company");
        if (notBlank(redirectUri)) form.add("redirect_uri", redirectUri);
        return postForm(TOKEN_PATH, form, null, null);
    }

    /**
     * Refreshes agency tokens. GHL refresh tokens are single-use: once this succeeds the old
     * refresh token is dead, so the caller must persist the returned one before anything else.
     */
    public TokenGrant refreshAgency(String refreshToken) {
        MultiValueMap<String, String> form = baseForm();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("user_type", "Company");
        if (notBlank(redirectUri)) form.add("redirect_uri", redirectUri);
        return postForm(TOKEN_PATH, form, null, null);
    }

    /** Mints a ~24h access token for one sub-account, using the agency token. Fails for locations outside the agency. */
    public TokenGrant mintLocationToken(String agencyAccessToken, String ghlCompanyId, String locationId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("companyId", ghlCompanyId);
        form.add("locationId", locationId);
        return postForm(LOCATION_TOKEN_PATH, form, agencyAccessToken, LOCATION_TOKEN_VERSION);
    }

    private MultiValueMap<String, String> baseForm() {
        if (!isConfigured()) {
            throw new GhlApiException(0, "GHL OAuth app is not configured (set CMO2_GHL_CLIENT_ID and CMO2_GHL_CLIENT_SECRET)");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        return form;
    }

    private TokenGrant postForm(String path, MultiValueMap<String, String> form, String bearer, String version) {
        try {
            RestClient.RequestBodySpec spec = client.post().uri(path)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON);
            if (bearer != null) spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
            if (version != null) spec.header("Version", version);
            JsonNode body = spec.body(form).retrieve().body(JsonNode.class);
            return toGrant(body);
        } catch (RestClientResponseException e) {
            throw new GhlApiException(e.getStatusCode().value(),
                    "GHL " + path + " returned " + e.getStatusCode().value() + ": " + trim(e.getResponseBodyAsString()));
        } catch (GhlApiException e) {
            throw e;
        } catch (Exception e) {
            throw new GhlApiException(0, "Could not reach GoHighLevel " + path + ": " + e.getMessage());
        }
    }

    static TokenGrant toGrant(JsonNode body) {
        if (body == null || !body.hasNonNull("access_token")) {
            throw new GhlApiException(502, "GHL token response had no access_token");
        }
        long expiresIn = body.path("expires_in").asLong(86_399);
        return new TokenGrant(
                body.get("access_token").asText(),
                text(body, "refresh_token"),
                Instant.now().plusSeconds(expiresIn),
                text(body, "scope"),
                text(body, "userType"),
                text(body, "companyId"),
                text(body, "locationId"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String trim(String s) {
        if (s == null) return "";
        String flat = s.replaceAll("\\s+", " ");
        return flat.length() > 300 ? flat.substring(0, 300) + "…" : flat;
    }
}
