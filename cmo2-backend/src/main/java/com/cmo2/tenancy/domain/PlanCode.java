package com.cmo2.tenancy.domain;

/**
 * Subscription plans with their enforced limits (Phase 7 wires these into
 * {@code PlanLimitGuard}). Each customer is one GHL connection, so ENTERPRISE — the plan for an
 * agency running CMO² for all its customers — has no connection cap.
 */
public enum PlanCode {
    STARTER(1, 3, 500_000L, 2),
    PRO(5, 10, 3_000_000L, 10),
    ENTERPRISE(Integer.MAX_VALUE, Integer.MAX_VALUE, 15_000_000L, Integer.MAX_VALUE);

    public final int maxCompanies;
    public final int maxConnections;
    public final long monthlyAiTokens;
    public final int maxSeats;

    PlanCode(int maxCompanies, int maxConnections, long monthlyAiTokens, int maxSeats) {
        this.maxCompanies = maxCompanies;
        this.maxConnections = maxConnections;
        this.monthlyAiTokens = monthlyAiTokens;
        this.maxSeats = maxSeats;
    }
}
