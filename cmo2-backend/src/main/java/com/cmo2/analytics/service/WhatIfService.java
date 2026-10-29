package com.cmo2.analytics.service;

import com.cmo2.analytics.repository.KpiSnapshotRepository;
import com.cmo2.integrations.core.Metrics;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * What-If simulator (Phase 8): projects how changes to spend, cost-per-lead, and conversion
 * rate ripple through a company's funnel, starting from its latest KPIs. A transparent
 * elasticity model (no hidden magic): spend ÷ CPL drives lead volume, conversion drives
 * appointments, and ROAS moves with CPL efficiency and conversion lift. Tenant-scoped.
 */
@Service
public class WhatIfService {

    private final KpiSnapshotRepository snapshots;

    public WhatIfService(KpiSnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    /** Percentage adjustments (e.g. +20 = +20%); any field may be null (no change). */
    public record Scenario(Double spendChangePct, Double cplChangePct, Double conversionChangePct) {}

    public record MetricDelta(String metric, String label, double baseline, double projected, double changePct) {}

    public record WhatIfResult(
            List<MetricDelta> metrics,
            double baselineSpend, double projectedSpend,
            double baselineRevenue, double projectedRevenue,
            String summary) {}

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public WhatIfResult simulate(Long companyId, Scenario scenario) {
        double leads = latest(companyId, Metrics.LEADS, 100);
        double cpl = latest(companyId, Metrics.CPL, 20);
        double roas = latest(companyId, Metrics.ROAS, 3);
        double conv = latest(companyId, Metrics.CONVERSION_RATE, 4);
        double appts = latest(companyId, Metrics.APPOINTMENTS, leads * conv / 100.0);

        double spendChange = pct(scenario.spendChangePct());
        double cplChange = pct(scenario.cplChangePct());
        double convChange = pct(scenario.conversionChangePct());

        double baselineSpend = leads * cpl;
        double newSpend = baselineSpend * (1 + spendChange);
        double newCpl = Math.max(0.01, cpl * (1 + cplChange));
        double newLeads = newSpend / newCpl;
        double newConv = Math.max(0, conv * (1 + convChange));
        double newAppts = appts * ratio(newLeads, leads) * ratio(newConv, conv);
        // ROAS improves as CPL falls and conversion rises.
        double newRoas = roas * ratio(cpl, newCpl) * ratio(newConv, conv);

        double baselineRevenue = baselineSpend * roas;
        double projectedRevenue = newSpend * newRoas;

        List<MetricDelta> metrics = List.of(
                delta(Metrics.LEADS, "Leads", leads, newLeads),
                delta(Metrics.CPL, "Cost per lead", cpl, newCpl),
                delta(Metrics.CONVERSION_RATE, "Conversion rate", conv, newConv),
                delta(Metrics.APPOINTMENTS, "Appointments", appts, newAppts),
                delta(Metrics.ROAS, "ROAS", roas, newRoas));

        return new WhatIfResult(metrics,
                round(baselineSpend), round(newSpend),
                round(baselineRevenue), round(projectedRevenue),
                summary(baselineRevenue, projectedRevenue, leads, newLeads));
    }

    private MetricDelta delta(String metric, String label, double base, double projected) {
        double changePct = base == 0 ? 0 : ((projected - base) / base) * 100.0;
        return new MetricDelta(metric, label, round(base), round(projected), round(changePct));
    }

    private String summary(double baseRev, double projRev, double baseLeads, double projLeads) {
        double revPct = baseRev == 0 ? 0 : ((projRev - baseRev) / baseRev) * 100.0;
        String revDir = revPct >= 0 ? "+" : "";
        double leadPct = baseLeads == 0 ? 0 : ((projLeads - baseLeads) / baseLeads) * 100.0;
        String leadDir = leadPct >= 0 ? "+" : "";
        return "Projected revenue " + revDir + fmt(revPct) + "% and leads " + leadDir + fmt(leadPct)
                + "% vs. the current baseline.";
    }

    private double latest(Long companyId, String metric, double fallback) {
        return snapshots.findFirstByCompanyIdAndMetricOrderByCapturedAtDesc(companyId, metric)
                .map(s -> s.getValue()).orElse(fallback);
    }

    private double pct(Double v) {
        return v == null ? 0 : v / 100.0;
    }

    private double ratio(double a, double b) {
        return b == 0 ? 1 : a / b;
    }

    private String fmt(double v) {
        return String.valueOf(Math.round(Math.abs(v) * 10.0) / 10.0);
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
