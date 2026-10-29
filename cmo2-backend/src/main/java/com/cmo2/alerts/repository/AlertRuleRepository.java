package com.cmo2.alerts.repository;

import com.cmo2.alerts.domain.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {

    List<AlertRule> findByCompanyIdOrderByCreatedAtDesc(Long companyId);

    /** Scheduler sweep — runs with NO tenant context, so it sees all tenants' active rules. */
    List<AlertRule> findByActiveTrue();
}
