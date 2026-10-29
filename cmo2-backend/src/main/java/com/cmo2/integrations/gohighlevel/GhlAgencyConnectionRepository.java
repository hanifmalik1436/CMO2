package com.cmo2.integrations.gohighlevel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GhlAgencyConnectionRepository extends JpaRepository<GhlAgencyConnection, Long> {

    /** Tenant-scoped by the Hibernate filter: the current tenant's agency connection, if any. */
    Optional<GhlAgencyConnection> findFirstByOrderByIdAsc();
}
