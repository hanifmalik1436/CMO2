package com.cmo2.analytics.repository;

import com.cmo2.analytics.domain.KpiDailyRollup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Every lookup names the tenant explicitly: rollups are also read from background loop threads. */
public interface KpiDailyRollupRepository extends JpaRepository<KpiDailyRollup, Long> {

    Optional<KpiDailyRollup> findByTenantIdAndCompanyIdAndPlatformAndMetricAndMetricDate(
            Long tenantId, Long companyId, String platform, String metric, LocalDate metricDate);

    Optional<KpiDailyRollup> findFirstByTenantIdAndCompanyIdAndPlatformAndMetricAndMetricDateLessThanOrderByMetricDateDesc(
            Long tenantId, Long companyId, String platform, String metric, LocalDate before);

    List<KpiDailyRollup> findByTenantIdAndCompanyIdAndMetricDateGreaterThanEqualOrderByMetricDateAsc(
            Long tenantId, Long companyId, LocalDate from);
}
