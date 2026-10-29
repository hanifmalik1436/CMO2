package com.cmo2.integrations.googleads;

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
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Google Ads API connector (OAuth2 user-consent; connector #11).
 *
 * <p><b>LIVE_UNVERIFIED:</b> no Google Ads developer token is available here. The Google
 * Ads REST API is GAQL-query-based ({@code POST /customers/{id}/googleAds:search}) and
 * additionally requires a {@code developer-token} header beyond the OAuth token — present
 * below with reasonable confidence from Google's published API conventions, but the exact
 * GAQL field names in {@link #pullData} are not exercised against a real account.
 * {@link PlatformCredentials#locationId()} carries the Google Ads customer id (digits only,
 * no dashes). Asset deployment is not implemented: creating a Google Ads ad requires a full
 * ad-group/responsive-search-ad object graph that a generic text asset doesn't map to safely.
 */
@Component
public class GoogleAdsConnector implements PlatformConnector {

    private static final Logger log = LoggerFactory.getLogger(GoogleAdsConnector.class);
    private static final String API_VERSION = "v17";
    private static final List<String> METRIC_PROFILE =
            List.of(Metrics.LEADS, Metrics.CPL, Metrics.ROAS, Metrics.CTR, Metrics.CONVERSION_RATE);

    private final boolean demoMode;
    private final String baseUrl;
    private final String developerToken;
    private final DemoConnector demoDelegate;

    public GoogleAdsConnector(
            @Value("${cmo2.integrations.demo-mode:true}") boolean demoMode,
            @Value("${cmo2.integrations.google-ads.base-url:https://googleads.googleapis.com}") String baseUrl,
            @Value("${cmo2.integrations.google-ads.developer-token:}") String developerToken) {
        this.demoMode = demoMode;
        this.baseUrl = baseUrl;
        this.developerToken = developerToken;
        this.demoDelegate = new DemoConnector(Platform.GOOGLE_ADS, METRIC_PROFILE, true, demoMode);
    }

    @Override
    public Platform platform() {
        return Platform.GOOGLE_ADS;
    }

    @Override
    public ConnectionHealth testConnection(PlatformCredentials credentials) {
        if (isDemo(credentials)) {
            return demoDelegate.testConnection(credentials);
        }
        if (developerToken.isBlank()) {
            return ConnectionHealth.red("cmo2.integrations.google-ads.developer-token is not configured");
        }
        try {
            search(credentials, "SELECT customer.id FROM customer LIMIT 1");
            return ConnectionHealth.green("Connected", null);
        } catch (Exception e) {
            log.warn("[LIVE_UNVERIFIED:GOOGLE_ADS] test connection failed: {}", e.getMessage());
            return ConnectionHealth.red("Invalid token, customer id, or developer token: " + e.getMessage());
        }
    }

    @Override
    public SyncResult pullData(SyncRequest request) {
        if (isDemo(request.credentials())) {
            return demoDelegate.pullData(request);
        }
        PlatformCredentials creds = request.credentials();
        if (creds == null || !creds.isOAuth()) {
            return SyncResult.failed("Google Ads is not connected (no OAuth token on file)");
        }
        if (developerToken.isBlank()) {
            return SyncResult.failed("cmo2.integrations.google-ads.developer-token is not configured");
        }
        try {
            JsonNode body = search(creds, "SELECT metrics.clicks, metrics.impressions, metrics.cost_micros, "
                    + "metrics.conversions FROM campaign WHERE segments.date DURING LAST_30_DAYS");
            List<JsonNode> rows = toList(body, "results");
            if (body == null || !body.has("results")) {
                return SyncResult.failed("Unexpected Google Ads response shape; GAQL field names need verification");
            }
            long clicks = 0, impressions = 0, conversions = 0;
            double costMicros = 0;
            for (JsonNode row : rows) {
                JsonNode m = row.path("metrics");
                clicks += m.path("clicks").asLong(0);
                impressions += m.path("impressions").asLong(0);
                conversions += m.path("conversions").asLong(0);
                costMicros += m.path("costMicros").asDouble(0);
            }
            double ctr = impressions == 0 ? 0 : round(100.0 * clicks / impressions);
            double cpl = conversions == 0 ? 0 : round((costMicros / 1_000_000.0) / conversions);
            double conversionRate = clicks == 0 ? 0 : round(100.0 * conversions / clicks);

            List<KpiPoint> kpis = List.of(
                    new KpiPoint(Metrics.LEADS, conversions),
                    new KpiPoint(Metrics.CPL, cpl),
                    new KpiPoint(Metrics.CTR, ctr),
                    new KpiPoint(Metrics.CONVERSION_RATE, conversionRate)
            );
            return new SyncResult(ConnectionHealth.green("Synced", null), kpis, (int) clicks, rows.size(), "Live sync OK");
        } catch (Exception e) {
            log.warn("[LIVE_UNVERIFIED:GOOGLE_ADS] pull failed: {}", e.getMessage());
            return SyncResult.failed("Sync failed: " + e.getMessage());
        }
    }

    @Override
    public DeployResult deployAsset(DeployRequest request) {
        if (demoMode || request.credentials() == null || isDemo(request.credentials())) {
            return demoDelegate.deployAsset(request);
        }
        return new DeployResult(false, null,
                "Creating a Google Ads ad requires a full ad-group/responsive-search-ad object graph "
                        + "that a generic text asset doesn't map to safely");
    }

    private boolean isDemo(PlatformCredentials creds) {
        return demoMode || creds == null || !creds.isOAuth();
    }

    private JsonNode search(PlatformCredentials creds, String gaql) {
        return RestClient.builder().baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + creds.accessToken())
                .defaultHeader("developer-token", developerToken)
                .build()
                .post()
                .uri("/" + API_VERSION + "/customers/{customerId}/googleAds:search",
                        Map.of("customerId", creds.locationId()))
                .body(Map.of("query", gaql))
                .retrieve().body(JsonNode.class);
    }

    private List<JsonNode> toList(JsonNode body, String field) {
        List<JsonNode> out = new ArrayList<>();
        if (body == null) return out;
        JsonNode arr = body.get(field);
        if (arr != null && arr.isArray()) arr.forEach(out::add);
        return out;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
