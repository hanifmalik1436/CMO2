package com.cmo2.analytics.repository;

import com.cmo2.analytics.domain.KpiSnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KpiSnapshotRepository extends JpaRepository<KpiSnapshot, Long> {

    /** Most-recent snapshots for a company (tenant-scoped by the Hibernate filter). */
    List<KpiSnapshot> findByCompanyIdOrderByCapturedAtDesc(Long companyId, Pageable pageable);

    /** Recent snapshots of one metric (newest first) — backs the time-series chart. */
    List<KpiSnapshot> findByCompanyIdAndMetricOrderByCapturedAtDesc(Long companyId, String metric, Pageable pageable);

    /** Latest value of one metric for a company — backs the alert rule engine. */
    Optional<KpiSnapshot> findFirstByCompanyIdAndMetricOrderByCapturedAtDesc(Long companyId, String metric);
}
