package com.cmo2.customers.repository;

import com.cmo2.customers.domain.CanonStatus;
import com.cmo2.customers.domain.CustomerCanon;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Lookups name the tenant explicitly: the approved canon is also read from loop threads. */
public interface CustomerCanonRepository extends JpaRepository<CustomerCanon, Long> {

    Optional<CustomerCanon> findFirstByTenantIdAndCompanyIdAndStatusOrderByIdDesc(Long tenantId, Long companyId,
                                                                                  CanonStatus status);

    List<CustomerCanon> findByTenantIdAndCompanyIdOrderByIdDesc(Long tenantId, Long companyId, Pageable page);

    @Query("select coalesce(max(c.versionNo), 0) from CustomerCanon c "
            + "where c.tenantId = :tenantId and c.companyId = :companyId")
    int maxVersionNo(@Param("tenantId") Long tenantId, @Param("companyId") Long companyId);
}
