package com.cmo2.analytics.service;

import com.cmo2.analytics.domain.KpiSnapshot;
import com.cmo2.analytics.repository.KpiSnapshotRepository;
import com.cmo2.integrations.core.Metrics;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Predictive analytics (Phase 8): projects a company's KPI forward with an ordinary
 * least-squares trend fit over recent snapshots, returning the projection, trend direction,
 * and an R²-based confidence. Deterministic and tenant-scoped (reads go through the Hibernate
 * tenant filter). The quantitative forecast is the substance; a rule-based narrative makes it
 * leadership-readable without spending the AI budget on every call.
 */
@Service
public class ForecastService {

    private static final int LOOKBACK = 60;
    private static final int MIN_POINTS = 3;

    private final KpiSnapshotRepository snapshots;

    public ForecastService(KpiSnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    public record ForecastPoint(int step, double value) {}

    public record Forecast(
            String metric,
            List<Double> history,
            List<ForecastPoint> projection,
            String trend,           // up | down | flat | insufficient_data
            double changePerPeriod, // absolute slope per period
            double pctPerPeriod,    // slope as % of the mean
            double confidence,      // 0..1 (R²)
            String narrative) {}

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public Forecast forecast(Long companyId, String metric, int horizon) {
        int steps = Math.min(Math.max(horizon, 1), 24);
        List<KpiSnapshot> recent = snapshots.findByCompanyIdAndMetricOrderByCapturedAtDesc(
                companyId, metric, PageRequest.of(0, LOOKBACK));

        List<Double> history = new ArrayList<>(recent.size());
        for (int i = recent.size() - 1; i >= 0; i--) {
            history.add(recent.get(i).getValue());
        }

        int n = history.size();
        if (n < MIN_POINTS) {
            double last = n == 0 ? 0 : history.get(n - 1);
            List<ForecastPoint> flat = new ArrayList<>();
            for (int s = 1; s <= steps; s++) flat.add(new ForecastPoint(s, round(last)));
            return new Forecast(metric, history, flat, "insufficient_data", 0, 0, 0,
                    "Not enough history yet to forecast " + label(metric) + " — sync a few more times.");
        }

        // OLS over x = 0..n-1
        double meanX = (n - 1) / 2.0;
        double meanY = history.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double sxx = 0, sxy = 0, syy = 0;
        for (int i = 0; i < n; i++) {
            double dx = i - meanX, dy = history.get(i) - meanY;
            sxx += dx * dx;
            sxy += dx * dy;
            syy += dy * dy;
        }
        double slope = sxx == 0 ? 0 : sxy / sxx;
        double intercept = meanY - slope * meanX;
        double r2 = (sxx == 0 || syy == 0) ? 0 : Math.max(0, Math.min(1, (sxy * sxy) / (sxx * syy)));

        List<ForecastPoint> projection = new ArrayList<>();
        for (int s = 1; s <= steps; s++) {
            double predicted = intercept + slope * (n - 1 + s);
            projection.add(new ForecastPoint(s, round(Math.max(0, predicted))));
        }

        double pctPerPeriod = meanY == 0 ? 0 : (slope / meanY) * 100.0;
        String trend = classify(slope, meanY);
        return new Forecast(metric, history, projection, trend,
                round(slope), round(pctPerPeriod), round(r2),
                narrative(metric, trend, pctPerPeriod, projection, steps, r2));
    }

    private String classify(double slope, double meanY) {
        double rel = meanY == 0 ? 0 : Math.abs(slope) / Math.abs(meanY);
        if (rel < 0.01) return "flat";
        return slope > 0 ? "up" : "down";
    }

    private String narrative(String metric, String trend, double pct, List<ForecastPoint> proj,
                             int steps, double r2) {
        String label = label(metric);
        String conf = r2 >= 0.6 ? "high" : r2 >= 0.3 ? "moderate" : "low";
        double target = proj.isEmpty() ? 0 : proj.get(proj.size() - 1).value();
        return switch (trend) {
            case "up" -> label + " is trending up ~" + fmt(Math.abs(pct)) + "% per period; "
                    + "projected near " + fmt(target) + " in " + steps + " periods (" + conf + " confidence).";
            case "down" -> label + " is trending down ~" + fmt(Math.abs(pct)) + "% per period; "
                    + "projected near " + fmt(target) + " in " + steps + " periods (" + conf + " confidence).";
            default -> label + " is holding steady; projected near " + fmt(target)
                    + " over the next " + steps + " periods (" + conf + " confidence).";
        };
    }

    private String label(String metric) {
        return switch (metric) {
            case Metrics.LEADS -> "Leads";
            case Metrics.CPL -> "Cost per lead";
            case Metrics.ROAS -> "ROAS";
            case Metrics.CTR -> "Click-through rate";
            case Metrics.CONVERSION_RATE -> "Conversion rate";
            case Metrics.APPOINTMENTS -> "Appointments";
            default -> Metrics.label(metric);
        };
    }

    private String fmt(double v) {
        return String.valueOf(Math.round(v * 10.0) / 10.0);
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
