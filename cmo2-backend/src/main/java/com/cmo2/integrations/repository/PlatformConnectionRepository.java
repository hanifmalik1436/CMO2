package com.cmo2.integrations.repository;

import com.cmo2.integrations.core.Platform;
import com.cmo2.integrations.domain.ConnectionStatus;
import com.cmo2.integrations.domain.PlatformConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlatformConnectionRepository extends JpaRepository<PlatformConnection, Long> {

    List<PlatformConnection> findByCompanyIdOrderByCreatedAtDesc(Long companyId);

    boolean existsByCompanyIdAndPlatform(Long companyId, Platform platform);

    Optional<PlatformConnection> findFirstByCompanyIdAndPlatform(Long companyId, Platform platform);

    /** Scheduler sweep — runs with NO tenant context, so it sees all tenants' connections. */
    List<PlatformConnection> findByStatus(ConnectionStatus status);

    /** Webhook tenant resolution — runs unauthenticated, so location_id is the lookup key. */
    Optional<PlatformConnection> findFirstByPlatformAndLocationId(Platform platform, String locationId);

    /** Live-connection count for the current tenant (plan-limit enforcement); excludes disconnected. */
    long countByStatusNot(ConnectionStatus status);
}
