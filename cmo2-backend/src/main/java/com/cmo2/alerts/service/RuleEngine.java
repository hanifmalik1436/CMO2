package com.cmo2.alerts.service;

import com.cmo2.alerts.domain.Alert;
import com.cmo2.alerts.domain.AlertRule;
import com.cmo2.alerts.domain.AlertStatus;
import com.cmo2.alerts.repository.AlertRepository;
import com.cmo2.analytics.domain.KpiSnapshot;
import com.cmo2.analytics.repository.KpiSnapshotRepository;
import com.cmo2.shared.audit.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Evaluates an alert rule against the latest KPI snapshot and fires an alert (once,
 * until acknowledged/resolved) through the configured channels. Must run with the
 * rule's tenant bound in context.
 */
@Service
public class RuleEngine {

    private final KpiSnapshotRepository snapshots;
    private final AlertRepository alertRepo;
    private final AlertDispatcher dispatcher;
    private final AuditService audit;

    public RuleEngine(KpiSnapshotRepository snapshots, AlertRepository alertRepo,
                      AlertDispatcher dispatcher, AuditService audit) {
        this.snapshots = snapshots;
        this.alertRepo = alertRepo;
        this.dispatcher = dispatcher;
        this.audit = audit;
    }

    /** Returns the fired alert if the rule tripped (and wasn't already open), else empty. */
    @Transactional
    public Optional<Alert> evaluate(AlertRule rule) {
        if (!rule.isActive()) return Optional.empty();

        Optional<KpiSnapshot> latest = snapshots
                .findFirstByCompanyIdAndMetricOrderByCapturedAtDesc(rule.getCompanyId(), rule.getMetric());
        if (latest.isEmpty()) return Optional.empty();

        double value = latest.get().getValue();
        if (!rule.getOperator().test(value, rule.getThreshold())) {
            return Optional.empty();
        }
        if (alertRepo.existsByRuleIdAndStatus(rule.getId(), AlertStatus.FIRED)) {
            return Optional.empty(); // already open — don't spam
        }

        Alert alert = new Alert();
        alert.setCompanyId(rule.getCompanyId());
        alert.setRuleId(rule.getId());
        alert.setMetric(rule.getMetric());
        alert.setValue(value);
        alert.setSeverity(rule.getSeverity());
        alert.setStatus(AlertStatus.FIRED);
        alert.setFiredAt(Instant.now());
        alert.setMessage(rule.getMetric() + " = " + value + " " + rule.getOperator()
                + " " + rule.getThreshold() + " (company " + rule.getCompanyId() + ")");

        dispatcher.dispatch(alert, rule.getChannelsJson());
        alert = alertRepo.save(alert);

        audit.record("ALERT_FIRED", "Alert", String.valueOf(alert.getId()), alert.getMessage());
        return Optional.of(alert);
    }
}
