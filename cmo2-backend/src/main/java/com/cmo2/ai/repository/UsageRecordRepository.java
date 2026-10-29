package com.cmo2.ai.repository;

import com.cmo2.ai.domain.UsageRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Tenant-scoped by the Hibernate filter, so these only ever see the current tenant's
 * usage rows (the methods don't need a tenant_id parameter).
 */
public interface UsageRecordRepository extends JpaRepository<UsageRecord, Long> {

    Optional<UsageRecord> findFirstByMetricAndPeriod(String metric, String period);

    @Query("select coalesce(sum(u.quantity), 0) from UsageRecord u where u.metric = :metric and u.period = :period")
    long sumQuantity(@Param("metric") String metric, @Param("period") String period);
}
