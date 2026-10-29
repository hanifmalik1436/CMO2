package com.cmo2.tenancy.repository;

import com.cmo2.tenancy.domain.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * All reads here are automatically tenant-scoped by the Hibernate filter
 * (see {@link com.cmo2.shared.tenancy.TenantFilterAspect}), so even a plain
 * {@code findAll()} only ever returns the current tenant's companies.
 */
public interface CompanyRepository extends JpaRepository<Company, Long> {

    List<Company> findAllByOrderByCreatedAtDesc();

    Optional<Company> findByName(String name);

    boolean existsByName(String name);

    Optional<Company> findByGhlLocationId(String ghlLocationId);

    /** Active-company count for the current tenant (plan-limit enforcement). */
    long countByStatus(com.cmo2.tenancy.domain.CompanyStatus status);
}
