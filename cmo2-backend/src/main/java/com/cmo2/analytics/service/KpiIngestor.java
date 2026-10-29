package com.cmo2.analytics.service;

import com.cmo2.analytics.domain.KpiDailyRollup;
import com.cmo2.analytics.domain.KpiSnapshot;
import com.cmo2.analytics.repository.KpiDailyRollupRepository;
import com.cmo2.analytics.repository.KpiSnapshotRepository;
import com.cmo2.integrations.core.Metrics;
import com.cmo2.integrations.core.SyncModels.KpiPoint;
import com.cmo2.shared.security.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Writes KPI snapshots. Called by the integrations sync — a clean cross-module
 * write (integrations → analytics) keeping the module boundary intact. Snapshots are
 * tenant-stamped automatically from the current context.
 */
@Service
public class KpiIngestor {

    private final KpiSnapshotRepository repository;
    private final KpiDailyRollupRepository rollups;

    public KpiIngestor(KpiSnapshotRepository repository, KpiDailyRollupRepository rollups) {
        this.repository = repository;
        this.rollups = rollups;
    }

    /** Snapshots only, no daily rollups — for one-off readings such as alert simulations. */
    @Transactional
    public int ingest(Long companyId, String platform, List<KpiPoint> points, Instant capturedAt) {
        for (KpiPoint p : points) {
            saveSnapshot(companyId, platform, p, capturedAt);
        }
        return points.size();
    }

    /**
     * A platform sync: snapshots plus the day's rollups, with {@code zone} deciding which local
     * day the reading belongs to. Daily figures are derived from running totals (see
     * {@link Metrics#DERIVED_FROM_TOTALS}): today's growth over the previous day's last reading or,
     * on the first day CMO² sees a customer, over today's first reading.
     *
     * @return how many points were stored, derived ones included
     */
    @Transactional
    public int ingest(Long companyId, String platform, List<KpiPoint> points, Instant capturedAt, ZoneId zone) {
        Long tenantId = TenantContext.requireTenantId();
        LocalDate day = LocalDate.ofInstant(capturedAt, zone);

        List<KpiPoint> all = new ArrayList<>(points);
        for (List<String> pair : Metrics.DERIVED_FROM_TOTALS) {
            String totalMetric = pair.get(0);
            String dailyMetric = pair.get(1);
            Optional<KpiPoint> total = points.stream().filter(p -> p.metric().equals(totalMetric)).findFirst();
            boolean alreadyGiven = points.stream().anyMatch(p -> p.metric().equals(dailyMetric));
            if (total.isEmpty() || alreadyGiven) continue;

            double baseline = rollups
                    .findFirstByTenantIdAndCompanyIdAndPlatformAndMetricAndMetricDateLessThanOrderByMetricDateDesc(
                            tenantId, companyId, platform, totalMetric, day)
                    .map(KpiDailyRollup::getValue)
                    .orElseGet(() -> rollups
                            .findByTenantIdAndCompanyIdAndPlatformAndMetricAndMetricDate(
                                    tenantId, companyId, platform, totalMetric, day)
                            .map(KpiDailyRollup::getOpenValue)
                            .orElse(total.get().value()));
            // Deleted contacts can shrink a total; a day never has negative new leads.
            all.add(new KpiPoint(dailyMetric, Math.max(0, total.get().value() - baseline)));
        }

        for (KpiPoint p : all) {
            saveSnapshot(companyId, platform, p, capturedAt);
            KpiDailyRollup r = rollups.findByTenantIdAndCompanyIdAndPlatformAndMetricAndMetricDate(
                    tenantId, companyId, platform, p.metric(), day).orElse(null);
            if (r == null) {
                r = new KpiDailyRollup();
                r.setCompanyId(companyId);
                r.setPlatform(platform);
                r.setMetric(p.metric());
                r.setMetricDate(day);
                r.setOpenValue(p.value());
                r.setSamples(0);
            }
            r.setValue(p.value());
            r.setSamples(r.getSamples() + 1);
            r.setLastCapturedAt(capturedAt);
            rollups.save(r);
        }
        return all.size();
    }

    private void saveSnapshot(Long companyId, String platform, KpiPoint p, Instant capturedAt) {
        KpiSnapshot snapshot = new KpiSnapshot();
        snapshot.setCompanyId(companyId);
        snapshot.setPlatform(platform);
        snapshot.setMetric(p.metric());
        snapshot.setValue(p.value());
        snapshot.setCapturedAt(capturedAt);
        repository.save(snapshot);
    }
}
