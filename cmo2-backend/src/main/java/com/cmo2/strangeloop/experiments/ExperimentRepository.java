package com.cmo2.strangeloop.experiments;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Lookups name the tenant explicitly: experiments are proposed and measured on loop threads. */
public interface ExperimentRepository extends JpaRepository<Experiment, Long> {

    List<Experiment> findByTenantIdAndCompanyIdOrderByCreatedAtDesc(Long tenantId, Long companyId, Pageable page);

    List<Experiment> findByTenantIdAndCompanyIdAndStatusOrderByCreatedAtAsc(Long tenantId, Long companyId, ExperimentStatus status);

    List<Experiment> findByTenantIdAndStatusInOrderByCreatedAtDesc(Long tenantId, Collection<ExperimentStatus> statuses);

    long countByTenantIdAndCompanyIdAndStatus(Long tenantId, Long companyId, ExperimentStatus status);

    Optional<Experiment> findFirstByTenantIdAndAssetId(Long tenantId, Long assetId);

    List<Experiment> findByTenantIdAndCompanyIdAndUpdatedAtGreaterThanEqualAndUpdatedAtLessThanOrderByUpdatedAtAsc(
            Long tenantId, Long companyId, Instant from, Instant to);
}
