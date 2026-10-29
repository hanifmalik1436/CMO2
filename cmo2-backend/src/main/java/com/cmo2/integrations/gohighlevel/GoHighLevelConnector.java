package com.cmo2.integrations.gohighlevel;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * GoHighLevel connector on the LeadConnector API v2, with every live call going through
 * {@link GhlGateway}.
 *
 * <ul>
 *   <li><b>Live</b>: reads the sub-account with either a per-location token minted from the
 *       agency connection, or a static Private Integration Token.</li>
 *   <li><b>Demo</b>: realistic, time-varying KPIs so the connect → sync → dashboard pipeline runs
 *       with no live account. Applies to static keys only — when the global demo flag is on, or
 *       the key starts with {@code demo}. Agency-linked sub-accounts are always live, so a real
 *       customer is never shown simulated numbers.</li>
 * </ul>
 */
@Component
public class GoHighLevelConnector implements PlatformConnector {

    private static final Logger log = LoggerFactory.getLogger(GoHighLevelConnector.class);

    private final boolean demoMode;
    private final GhlGateway gateway;
    private final GhlLocationReader reader;

    public GoHighLevelConnector(@Value("${cmo2.integrations.gohighlevel.demo-mode:true}") boolean demoMode,
                                GhlGateway gateway, GhlLocationReader reader) {
        this.demoMode = demoMode;
        this.gateway = gateway;
        this.reader = reader;
    }

    @Override
    public Platform platform() {
        return Platform.GOHIGHLEVEL;
    }

    @Override
    public ConnectionHealth testConnection(PlatformCredentials credentials) {
        if (isDemo(credentials)) {
            return ConnectionHealth.green("Connected (demo mode)", 12);
        }
        String locationId = credentials.locationId();
        if (locationId == null || locationId.isBlank()) {
            return ConnectionHealth.red("No GHL sub-account (location id) is linked to this connection");
        }
        try {
            long start = System.nanoTime();
            gateway.get("/locations/" + locationId, Map.of(), credentials.bearerToken(), locationId);
            int latency = (int) Duration.ofNanos(System.nanoTime() - start).toMillis();
            return ConnectionHealth.green("Connected", latency);
        } catch (GhlApiException e) {
            log.warn("GHL test connection failed for {}: {}", locationId, e.getMessage());
            return ConnectionHealth.red(e.isUnauthorized() ? "GHL rejected the access token" : "Could not reach GoHighLevel");
        }
    }

    @Override
    public SyncResult pullData(SyncRequest request) {
        PlatformCredentials creds = request.credentials();
        if (isDemo(creds)) {
            return demoSync(request.companyId());
        }
        String locationId = creds.locationId();
        if (locationId == null || locationId.isBlank()) {
            return SyncResult.failed("No GHL sub-account (location id) is linked to this connection");
        }
        GhlLocationReader.Snapshot snap = reader.read(locationId, creds.bearerToken(), request.timezone());
        if (snap.tokenRejected()) {
            return SyncResult.failed("GHL rejected the access token");
        }
        if (snap.kpis().isEmpty()) {
            return new SyncResult(ConnectionHealth.red("Could not read any figures from GoHighLevel"),
                    List.of(), 0, 0, "Sync failed: " + describe(snap.failed()), snap.facts());
        }

        StringBuilder message = new StringBuilder(snap.failed().isEmpty() ? "Live sync OK" : "Synced, but couldn't read "
                + describe(snap.failed()));
        if (!snap.notes().isEmpty()) {
            message.append(" (").append(String.join("; ", snap.notes())).append(')');
        }
        ConnectionHealth health = snap.failed().isEmpty()
                ? ConnectionHealth.green("Synced", null)
                : ConnectionHealth.yellow(message.toString());
        return new SyncResult(health, snap.kpis(), (int) Math.min(Integer.MAX_VALUE, value(snap, Metrics.CONTACTS_TOTAL)),
                (int) Math.min(Integer.MAX_VALUE, value(snap, Metrics.OPPORTUNITIES_OPEN)), message.toString(),
                snap.facts());
    }

    private static String describe(Map<String, String> failed) {
        return failed.entrySet().stream().map(e -> e.getKey() + " (" + e.getValue() + ")")
                .collect(Collectors.joining(", "));
    }

    private static long value(GhlLocationReader.Snapshot snap, String metric) {
        return snap.kpis().stream().filter(k -> k.metric().equals(metric)).findFirst()
                .map(k -> (long) k.value()).orElse(0L);
    }

    @Override
    public DeployResult deployAsset(DeployRequest request) {
        if (request.credentials() == null || isDemo(request.credentials())) {
            String ref = "ghl-demo-" + Integer.toHexString(Objects.hash(
                    request.companyId(), request.assetType(), System.identityHashCode(request)));
            return new DeployResult(true, ref, "Deployed to GoHighLevel (demo mode)");
        }
        // Live social posts are scheduled by the approval flow through GhlSocialPlanner, which needs
        // the approval and the chosen accounts; nothing else has a live GHL destination yet.
        return new DeployResult(false, null, "SOCIAL_MEDIA".equals(request.assetType())
                ? "Publish approved social posts from the approval queue"
                : "Only social posts can be published to GoHighLevel so far; email waits on the email spike");
    }

    /** True when these credentials reach a real sub-account (never for demo keys). */
    public boolean isLive(PlatformCredentials creds) {
        return creds != null && !isDemo(creds);
    }

    private boolean isDemo(PlatformCredentials creds) {
        if (creds == null || creds.isOAuth()) {
            return false;
        }
        return demoMode || (creds.apiKey() != null && creds.apiKey().toLowerCase().startsWith("demo"));
    }

    /** Realistic, mildly time-varying KPIs so the dashboard visibly refreshes each sync. */
    private SyncResult demoSync(Long companyId) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        long seed = companyId == null ? 0 : companyId;
        double base = 60 + (seed % 40); // per-company baseline so companies differ

        List<KpiPoint> kpis = List.of(
                new KpiPoint(Metrics.LEADS, round(base + r.nextInt(-8, 12))),
                new KpiPoint(Metrics.CPL, round(18 + r.nextDouble(-3, 6))),
                new KpiPoint(Metrics.ROAS, round(3.2 + r.nextDouble(-0.6, 1.1))),
                new KpiPoint(Metrics.CTR, round(2.6 + r.nextDouble(-0.5, 0.9))),
                new KpiPoint(Metrics.CONVERSION_RATE, round(4.0 + r.nextDouble(-1.0, 1.5))),
                new KpiPoint(Metrics.APPOINTMENTS, round(12 + r.nextInt(-4, 7)))
        );
        return new SyncResult(ConnectionHealth.green("Synced (demo mode)", 14),
                kpis, (int) base, 4, "Demo sync OK");
    }

    private double round(double v) {
        return Math.round(Math.max(0, v) * 100.0) / 100.0;
    }
}
