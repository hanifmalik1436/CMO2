package com.cmo2.integrations.nureply;

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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cold-email outreach platform (connector #3).
 *
 * <p><b>LIVE_UNVERIFIED:</b> no Nureply developer account is available here — the
 * {@code /v1/campaigns} shape is a best-effort reading of Nureply's public API, not
 * exercised against a real account. Degrades honestly on any unexpected response rather
 * than fabricating success.
 */
@Component
public class NureplyConnector implements PlatformConnector {

    private static final Logger log = LoggerFactory.getLogger(NureplyConnector.class);
    private static final List<String> METRIC_PROFILE = List.of(Metrics.LEADS, Metrics.CTR, Metrics.CONVERSION_RATE);

    private final boolean demoMode;
    private final String baseUrl;
    private final DemoConnector demoDelegate;

    public NureplyConnector(
            @Value("${cmo2.integrations.demo-mode:true}") boolean demoMode,
            @Value("${cmo2.integrations.nureply.base-url:https://app.nureply.com/api}") String baseUrl) {
        this.demoMode = demoMode;
        this.baseUrl = baseUrl;
        this.demoDelegate = new DemoConnector(Platform.NUREPLY, METRIC_PROFILE, true, demoMode);
    }

    @Override
    public Platform platform() {
        return Platform.NUREPLY;
    }

    @Override
    public ConnectionHealth testConnection(PlatformCredentials credentials) {
        if (isDemo(credentials)) {
            return demoDelegate.testConnection(credentials);
        }
        try {
            client(credentials).get().uri("/v1/campaigns?limit=1").retrieve().toBodilessEntity();
            return ConnectionHealth.green("Connected", null);
        } catch (Exception e) {
            log.warn("[LIVE_UNVERIFIED:NUREPLY] test connection failed: {}", e.getMessage());
            return ConnectionHealth.red("Could not reach Nureply: " + e.getMessage());
        }
    }

    @Override
    public SyncResult pullData(SyncRequest request) {
        if (isDemo(request.credentials())) {
            return demoDelegate.pullData(request);
        }
        try {
            JsonNode body = client(request.credentials()).get()
                    .uri("/v1/campaigns").retrieve().body(JsonNode.class);
            if (body == null || !body.has("data")) {
                return SyncResult.failed("Unexpected Nureply response shape; live pullData needs endpoint verification");
            }
            List<JsonNode> campaigns = toList(body, "data");
            long sent = campaigns.stream().mapToLong(c -> number(c, "sent")).sum();
            long clicked = campaigns.stream().mapToLong(c -> number(c, "clicked")).sum();
            long replied = campaigns.stream().mapToLong(c -> number(c, "replied")).sum();
            double ctr = sent == 0 ? 0 : round(100.0 * clicked / sent);
            double conversionRate = sent == 0 ? 0 : round(100.0 * replied / sent);

            List<KpiPoint> kpis = List.of(
                    new KpiPoint(Metrics.LEADS, sent),
                    new KpiPoint(Metrics.CTR, ctr),
                    new KpiPoint(Metrics.CONVERSION_RATE, conversionRate)
            );
            return new SyncResult(ConnectionHealth.green("Synced", null), kpis, (int) sent, campaigns.size(), "Live sync OK");
        } catch (Exception e) {
            log.warn("[LIVE_UNVERIFIED:NUREPLY] pull failed: {}", e.getMessage());
            return SyncResult.failed("Sync failed: " + e.getMessage());
        }
    }

    @Override
    public DeployResult deployAsset(DeployRequest request) {
        if (demoMode || request.credentials() == null || isDemo(request.credentials())) {
            return demoDelegate.deployAsset(request);
        }
        try {
            JsonNode body = client(request.credentials()).post()
                    .uri("/v1/campaigns")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("name", request.assetType(), "content", request.contentJson()))
                    .retrieve().body(JsonNode.class);
            String ref = body != null && body.hasNonNull("id") ? body.get("id").asText() : null;
            return new DeployResult(ref != null, ref, ref != null ? "Deployed to Nureply" : "Unexpected response from Nureply");
        } catch (Exception e) {
            log.warn("[LIVE_UNVERIFIED:NUREPLY] deploy failed: {}", e.getMessage());
            return new DeployResult(false, null, "Deploy failed: " + e.getMessage());
        }
    }

    private boolean isDemo(PlatformCredentials creds) {
        return demoMode || (creds != null && creds.apiKey() != null && creds.apiKey().toLowerCase().startsWith("demo"));
    }

    private RestClient client(PlatformCredentials creds) {
        return RestClient.builder().baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + creds.apiKey()).build();
    }

    private List<JsonNode> toList(JsonNode body, String field) {
        List<JsonNode> out = new ArrayList<>();
        if (body == null) return out;
        JsonNode arr = body.get(field);
        if (arr != null && arr.isArray()) arr.forEach(out::add);
        return out;
    }

    private long number(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? 0 : n.asLong();
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
