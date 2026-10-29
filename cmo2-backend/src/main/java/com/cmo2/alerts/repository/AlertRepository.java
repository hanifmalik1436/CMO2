package com.cmo2.alerts.repository;

import com.cmo2.alerts.domain.Alert;
import com.cmo2.alerts.domain.AlertStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    List<Alert> findByOrderByFiredAtDesc(Pageable pageable);

    /** Suppress duplicate firings — is there already an open alert for this rule? */
    boolean existsByRuleIdAndStatus(Long ruleId, AlertStatus status);
}
