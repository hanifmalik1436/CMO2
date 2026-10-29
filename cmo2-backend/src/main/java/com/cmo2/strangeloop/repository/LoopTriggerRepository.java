package com.cmo2.strangeloop.repository;

import com.cmo2.strangeloop.domain.LoopTrigger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LoopTriggerRepository extends JpaRepository<LoopTrigger, Long> {

    List<LoopTrigger> findByCompanyIdAndActiveTrue(Long companyId);

    Optional<LoopTrigger> findFirstByTenantIdAndCompanyIdAndTriggerType(Long tenantId, Long companyId, String triggerType);

    List<LoopTrigger> findByTenantIdAndTriggerTypeAndActiveTrue(Long tenantId, String triggerType);

    /** Startup reconciliation across every tenant (no tenant bound). */
    List<LoopTrigger> findByTriggerTypeAndActiveTrue(String triggerType);
}
