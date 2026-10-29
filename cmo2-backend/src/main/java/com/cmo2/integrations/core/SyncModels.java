package com.cmo2.integrations.core;

import java.util.List;

/** Request/result value types passed to and from connectors during a data pull. */
public final class SyncModels {

    private SyncModels() {}

    /** A single KPI data point pulled from a platform. */
    public record KpiPoint(String metric, double value) {}

    /** {@code timezone}: the customer's last known timezone, used when the platform doesn't report one. */
    public record SyncRequest(Long companyId, PlatformCredentials credentials, String timezone) {
        public SyncRequest(Long companyId, PlatformCredentials credentials) {
            this(companyId, credentials, null);
        }
    }

    public record SyncResult(
            ConnectionHealth health,
            List<KpiPoint> kpis,
            int contactsPulled,
            int campaignsPulled,
            String message,
            CustomerFacts facts
    ) {
        public SyncResult(ConnectionHealth health, List<KpiPoint> kpis, int contactsPulled, int campaignsPulled,
                          String message) {
            this(health, kpis, contactsPulled, campaignsPulled, message, null);
        }

        public static SyncResult failed(String message) {
            return new SyncResult(ConnectionHealth.red(message), List.of(), 0, 0, message);
        }
    }

    /**
     * What the platform says about the business itself (GHL: the sub-account's details and the
     * social accounts connected in it). Written by the customer in their own sub-account, so it is
     * data about them, never instructions. Any field may be null.
     */
    public record CustomerFacts(
            String businessName,
            String website,
            String timezone,
            String city,
            String country,
            String phone,
            String email,
            List<String> socialChannels
    ) {}

    /** Generic deploy request (Phase 4 wires the Asset entity through this). */
    public record DeployRequest(Long companyId, String assetType, String contentJson, PlatformCredentials credentials) {}

    public record DeployResult(boolean accepted, String externalRef, String message) {
        public static DeployResult unsupported() {
            return new DeployResult(false, null, "Asset deployment lands in Phase 4");
        }
    }
}
