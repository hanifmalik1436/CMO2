package com.cmo2.analytics.service;

import com.cmo2.analytics.domain.KpiSnapshot;
import com.cmo2.analytics.repository.KpiSnapshotRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read side for KPI snapshots. Phase 3 needs only "latest value per metric" to drive
 * the dashboard; richer aggregation/trends arrive in Phase 6.
 */
@Service
public class KpiQueryService {

    private static final int RECENT_WINDOW = 300;

    private final KpiSnapshotRepository repository;

    public KpiQueryService(KpiSnapshotRepository repository) {
        this.repository = repository;
    }

    public record LatestKpi(String metric, double value, Instant capturedAt) {}

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public List<LatestKpi> latestByCompany(Long companyId) {
        // Snapshots come back newest-first; the first occurrence of each metric is its latest.
        List<KpiSnapshot> recent =
                repository.findByCompanyIdOrderByCapturedAtDesc(companyId, PageRequest.of(0, RECENT_WINDOW));

        Map<String, LatestKpi> latest = new LinkedHashMap<>();
        for (KpiSnapshot s : recent) {
            latest.putIfAbsent(s.getMetric(), new LatestKpi(s.getMetric(), s.getValue(), s.getCapturedAt()));
        }
        return new ArrayList<>(latest.values());
    }
}
