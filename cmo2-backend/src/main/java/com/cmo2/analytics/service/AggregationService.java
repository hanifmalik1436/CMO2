package com.cmo2.analytics.service;

import com.cmo2.analytics.domain.KpiSnapshot;
import com.cmo2.analytics.repository.KpiSnapshotRepository;
import com.cmo2.integrations.core.Metrics;
import com.cmo2.tenancy.domain.Company;
import com.cmo2.tenancy.repository.CompanyRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Read-side KPI aggregation for the Analytics module: per-company time series and a
 * portfolio roll-up across the tenant's companies. All reads are tenant-scoped.
 */
@Service
public class AggregationService {

    private static final Set<String> SUMMED = Set.of(Metrics.LEADS, Metrics.APPOINTMENTS, Metrics.WON,
            Metrics.REVENUE, Metrics.PIPELINE_VALUE);

    private final KpiSnapshotRepository snapshots;
    private final CompanyRepository companies;

    public AggregationService(KpiSnapshotRepository snapshots, CompanyRepository companies) {
        this.snapshots = snapshots;
        this.companies = companies;
    }

    public record Point(Instant capturedAt, double value) {}

    public record MetricAggregate(String metric, double value, int companiesWithData) {}

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public List<Point> timeseries(Long companyId, String metric, int limit) {
        List<KpiSnapshot> recent = snapshots.findByCompanyIdAndMetricOrderByCapturedAtDesc(
                companyId, metric, PageRequest.of(0, Math.min(Math.max(limit, 1), 500)));
        List<Point> points = new ArrayList<>(recent.size());
        for (int i = recent.size() - 1; i >= 0; i--) { // ascending for charting
            points.add(new Point(recent.get(i).getCapturedAt(), recent.get(i).getValue()));
        }
        return points;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public List<MetricAggregate> portfolio() {
        List<Company> all = companies.findAllByOrderByCreatedAtDesc();
        List<MetricAggregate> out = new ArrayList<>();
        for (String metric : Metrics.ALL) {
            double total = 0;
            int n = 0;
            for (Company c : all) {
                var latest = snapshots.findFirstByCompanyIdAndMetricOrderByCapturedAtDesc(c.getId(), metric);
                if (latest.isPresent()) {
                    total += latest.get().getValue();
                    n++;
                }
            }
            double value = n == 0 ? 0 : (SUMMED.contains(metric) ? total : total / n);
            out.add(new MetricAggregate(metric, round(value), n));
        }
        return out;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
