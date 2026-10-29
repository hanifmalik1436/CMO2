package com.cmo2.integrations.connectors;

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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A reusable {@link PlatformConnector} for connectors #2–#15 (Phase 8 expansion). Each
 * platform replicates the GoHighLevel reference contract; here the shared <b>demo</b>
 * behaviour is parameterised by a platform-appropriate KPI profile, so the full
 * connect → sync → dashboard → forecast pipeline runs for every platform with no live
 * account. Live API wiring per platform is the per-connector follow-on (same as the
 * GoHighLevel live path), gated by {@code cmo2.integrations.demo-mode}.
 */
public class DemoConnector implements PlatformConnector {

    private final Platform platform;
    private final List<String> metricProfile;
    private final boolean canDeploy;
    private final boolean demoMode;

    public DemoConnector(Platform platform, List<String> metricProfile, boolean canDeploy, boolean demoMode) {
        this.platform = platform;
        this.metricProfile = metricProfile;
        this.canDeploy = canDeploy;
        this.demoMode = demoMode;
    }

    @Override
    public Platform platform() {
        return platform;
    }

    @Override
    public boolean deploysAssets() {
        return canDeploy;
    }

    @Override
    public ConnectionHealth testConnection(PlatformCredentials credentials) {
        if (isDemo(credentials)) {
            return ConnectionHealth.green("Connected (demo mode)", 10 + (platform.ordinal() % 9));
        }
        // No live API client is wired for this platform yet — be honest rather than fabricate.
        return ConnectionHealth.yellow("Credentials stored; live " + platform.displayName
                + " API integration is pending (use demo mode for KPIs).");
    }

    @Override
    public SyncResult pullData(SyncRequest request) {
        if (!isDemo(request.credentials())) {
            return new SyncResult(
                    ConnectionHealth.yellow("Live " + platform.displayName + " sync pending"),
                    List.of(), 0, 0, "Enable demo mode or wire the live API for KPI ingestion.");
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        long seed = request.companyId() == null ? 0 : request.companyId();
        List<KpiPoint> kpis = new ArrayList<>(metricProfile.size());
        for (String metric : metricProfile) {
            kpis.add(new KpiPoint(metric, demoValue(metric, seed, r)));
        }
        int contacts = 20 + (int) (seed % 50) + r.nextInt(0, 15);
        return new SyncResult(ConnectionHealth.green("Synced (demo mode)", 12 + r.nextInt(0, 8)),
                kpis, contacts, r.nextInt(1, 5), "Demo sync OK");
    }

    @Override
    public DeployResult deployAsset(DeployRequest request) {
        if (!canDeploy) {
            return new DeployResult(false, null, platform.displayName + " is a data source — assets aren't deployed here.");
        }
        if (demoMode) {
            String ref = platform.name().toLowerCase() + "-demo-" + Integer.toHexString(java.util.Objects.hash(
                    request.companyId(), request.assetType(), System.identityHashCode(request)));
            return new DeployResult(true, ref, "Deployed to " + platform.displayName + " (demo mode)");
        }
        return DeployResult.unsupported();
    }

    private boolean isDemo(PlatformCredentials creds) {
        return demoMode || (creds != null && creds.apiKey() != null && creds.apiKey().toLowerCase().startsWith("demo"));
    }

    /** Realistic, mildly time-varying values per metric so dashboards visibly refresh each sync. */
    private double demoValue(String metric, long seed, ThreadLocalRandom r) {
        double v = switch (metric) {
            case Metrics.LEADS -> 50 + (seed % 40) + r.nextInt(-8, 14);
            case Metrics.CPL -> 16 + r.nextDouble(-3, 7);
            case Metrics.ROAS -> 3.0 + r.nextDouble(-0.7, 1.4);
            case Metrics.CTR -> 2.4 + r.nextDouble(-0.5, 1.1);
            case Metrics.CONVERSION_RATE -> 3.8 + r.nextDouble(-1.0, 1.8);
            case Metrics.APPOINTMENTS -> 10 + (seed % 8) + r.nextInt(-4, 8);
            default -> 1 + r.nextDouble(0, 5);
        };
        return Math.round(Math.max(0, v) * 100.0) / 100.0;
    }
}
