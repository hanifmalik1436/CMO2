package com.cmo2.docs.repository;

import com.cmo2.docs.domain.CompanyDocTargets;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Lookups take the tenant id explicitly, for the same reason as {@link DocConnectionRepository}. */
public interface CompanyDocTargetsRepository extends JpaRepository<CompanyDocTargets, Long> {

    Optional<CompanyDocTargets> findByTenantIdAndCompanyId(Long tenantId, Long companyId);

    List<CompanyDocTargets> findByTenantId(Long tenantId);
}
