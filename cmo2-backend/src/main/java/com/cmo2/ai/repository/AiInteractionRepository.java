package com.cmo2.ai.repository;

import com.cmo2.ai.domain.AiInteraction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/** Lookups name the tenant explicitly: AI calls are also recorded and read from loop threads. */
public interface AiInteractionRepository extends JpaRepository<AiInteraction, Long> {

    List<AiInteraction> findByTenantIdAndCompanyIdOrderByCreatedAtDesc(Long tenantId, Long companyId, Pageable page);

    List<AiInteraction> findByTenantIdAndCompanyIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
            Long tenantId, Long companyId, Instant from, Instant to);

    @Query("select coalesce(sum(a.inputTokens + a.outputTokens), 0) from AiInteraction a "
            + "where a.tenantId = :tenantId and a.companyId = :companyId and a.createdAt >= :from")
    long tokensSince(@Param("tenantId") Long tenantId, @Param("companyId") Long companyId, @Param("from") Instant from);

    /** Retention sweep across every tenant (runs with no tenant bound). */
    @Modifying
    @Query("delete from AiInteraction a where a.createdAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
