package com.cmo2.customers.repository;

import com.cmo2.customers.domain.CustomerProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Lookups name the tenant explicitly: profiles are also read and written from background threads. */
public interface CustomerProfileRepository extends JpaRepository<CustomerProfile, Long> {

    Optional<CustomerProfile> findByTenantIdAndCompanyId(Long tenantId, Long companyId);
}
