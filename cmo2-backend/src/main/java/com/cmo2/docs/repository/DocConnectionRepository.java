package com.cmo2.docs.repository;

import com.cmo2.docs.domain.DocConnection;
import com.cmo2.docs.domain.DocProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Lookups take the tenant id explicitly: these are also used from the Strange Loop's background
 * threads, where the Hibernate tenant filter can't be relied on outside a transaction.
 */
public interface DocConnectionRepository extends JpaRepository<DocConnection, Long> {

    Optional<DocConnection> findByTenantIdAndProvider(Long tenantId, DocProvider provider);

    List<DocConnection> findByTenantId(Long tenantId);
}
