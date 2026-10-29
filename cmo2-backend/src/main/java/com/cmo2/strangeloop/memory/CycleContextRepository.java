package com.cmo2.strangeloop.memory;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Lookups name the tenant explicitly: cycle records are read and written on loop threads. */
public interface CycleContextRepository extends JpaRepository<CycleContext, Long> {

    Optional<CycleContext> findByTenantIdAndIterationId(Long tenantId, Long iterationId);

    List<CycleContext> findByTenantIdAndCompanyIdOrderByPeriodEndDesc(Long tenantId, Long companyId, Pageable page);

    Optional<CycleContext> findFirstByTenantIdAndCompanyIdAndIterationIdNotOrderByPeriodEndDesc(
            Long tenantId, Long companyId, Long iterationId);
}
