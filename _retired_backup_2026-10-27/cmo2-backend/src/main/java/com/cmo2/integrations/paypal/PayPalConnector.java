package com.cmo2.integrations.paypal;

import com.cmo2.integrations.connectors.DemoConnector;
import com.cmo2.integrations.core.ConnectionHealth;
import com.cmo2.integrations.core.Metrics;
import com.cmo2.integrations.core.Platform;
import com.cmo2.integrations.core.PlatformConnector;
import com.cmo2.integrations.core.PlatformCredentials;
import com.cmo2.integrations.core.SyncModels.DeployRequest;
import com.cmo2.integrations.core.SyncModels.DeployResult;
import com.cmo2.integrations.core.SyncModels.KpiPoint;
import com.cmo2.integrations.core.SyncModels.SyncRequest;
import com.cmo2.integrations.core.SyncModels.SyncResult;
import com.cmo2.integrations.oauth.OAuthService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Reads the tenant's own connected PayPal account. Unlike the 5 user-consent OAuth
 * platforms, PayPal uses OAuth2 <b>client-credentials</b> (no browser redirect) — the
 * tenant pastes their app's client id + secret into the same "API key" field every
 * connector uses, as {@code clientId:clientSecret} (PayPal's own REST app-credential
 * pairing convention). A fresh access token is fetched per call via
 * {@link OAuthService#clientCredentials}; data-only ({@link #deploysAssets()} is false).
 */
@Component
public class PayPalConnector implements PlatformConnector {

    private static final Logger log = LoggerFactory.getLogger(PayPalConnector.class);

    private final boolean demoMode;
    private final String baseUrl;
    private final String tokenUrl;
    private final OAuthService oauthService;
    private final DemoConnector demoDelegate;

    public PayPalConnector(
            @Value("${cmo2.integrations.demo-mode:true}") boolean demoMode,
            @Value("${cmo2.integrations.paypal.base-url:https://api-m.paypal.com}") String baseUrl,
            @Value("${cmo2.integrations.paypal.token-url:https://api-m.paypal.com/v1/oauth2/token}") String tokenUrl,
            OAuthService oauthService) {
        this.demoMode = demoMode;
        this.baseUrl = baseUrl;
        this.tokenUrl = tokenUrl;
        this.oauthService = oauthService;
        this.demoDelegate = new DemoConnector(Platform.PAYPAL,
                List.of(Metrics.LEADS, Metrics.ROAS, Metrics.CONVERSION_RATE), false, demoMode);
    }

    @Override
    public Platform platform() {
        return Platform.PAYPAL;
    }

    @Override
    public boolean deploysAssets() {
        return false;
    }

    @Override
    public ConnectionHealth testConnection(PlatformCredentials credentials) {
        if (isDemo(credentials)) {
            return demoDelegate.testConnection(credentials);
        }
        try {
            accessToken(credentials);
            return ConnectionHealth.green("Connected", null);
        } catch (Exception e) {
            log.warn("PayPal test connection failed: {}", e.getMessage());
            return ConnectionHealth.red("Invalid client id/secret or unreachable: " + e.getMessage());
        }
    }

    @Override
    public SyncResult pullData(SyncRequest request) {
        if (isDemo(request.credentials())) {
            return demoDelegate.pullData(request);
        }
        try {
            String token = accessToken(request.credentials());
            Instant end = Instant.now();
            Instant start = end.minus(30, ChronoUnit.DAYS);
            JsonNode body = RestClient.builder().baseUrl(baseUrl).build().get()
                    .uri(uri -> uri.path("/v1/reporting/transactions")
                            .queryParam("start_date", start.toString())
                            .queryParam("end_date", end.toString())
                            .queryParam("page_size", 100)
                            .build())
                    .header("Authorization", "Bearer " + token)
                    .retrieve().body(JsonNode.class);

            List<JsonNode> details = toList(body);
            long completed = details.stream()
                    .filter(d -> "S".equals(text(d.path("transaction_info"), "transaction_status"))).count();
            double conversionRate = details.isEmpty() ? 0 : round(100.0 * completed / details.size());

            List<KpiPoint> kpis = List.of(
                    new KpiPoint(Metrics.LEADS, completed),
                    new KpiPoint(Metrics.CONVERSION_RATE, conversionRate)
            );
            return new SyncResult(ConnectionHealth.green("Synced", null), kpis,
                    (int) completed, 0, "Live sync OK (revenue/ROAS requires ad-spend data CMO2 does not have)");
        } catch (Exception e) {
            log.warn("PayPal pull failed: {}", e.getMessage());
            return SyncResult.failed("Sync failed: " + e.getMessage());
        }
    }

    @Override
    public DeployResult deployAsset(DeployRequest request) {
        return DeployResult.unsupported();
    }

    private String accessToken(PlatformCredentials credentials) {
        String[] parts = splitCredentials(credentials);
        return oauthService.clientCredentials(tokenUrl, parts[0], parts[1]).accessToken();
    }

    private String[] splitCredentials(PlatformCredentials credentials) {
        String raw = credentials.apiKey();
        int idx = raw == null ? -1 : raw.indexOf(':');
        if (idx < 0) {
            throw new IllegalArgumentException(
                    "PayPal credentials must be pasted as clientId:clientSecret");
        }
        return new String[] { raw.substring(0, idx), raw.substring(idx + 1) };
    }

    private boolean isDemo(PlatformCredentials creds) {
        return demoMode || (creds != null && creds.apiKey() != null && creds.apiKey().toLowerCase().startsWith("demo"));
    }

    private List<JsonNode> toList(JsonNode body) {
        List<JsonNode> out = new java.util.ArrayList<>();
        if (body == null) return out;
        JsonNode data = body.get("transaction_details");
        if (data != null && data.isArray()) {
            data.forEach(out::add);
        }
        return out;
    }

    private String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
