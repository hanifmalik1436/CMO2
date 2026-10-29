package com.cmo2.alerts.service;

import com.cmo2.alerts.domain.AlertRule;
import com.cmo2.alerts.repository.AlertRuleRepository;
import com.cmo2.shared.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Background alert evaluation. Sweeps active rules across all tenants on a short interval
 * so a breach fires well within the "5-minute" target. Binds each rule's tenant before
 * evaluating it (the sweep query runs with no tenant context, by design).
 */
@Component
public class AlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(AlertScheduler.class);

    private final AlertRuleRepository ruleRepo;
    private final RuleEngine ruleEngine;

    public AlertScheduler(AlertRuleRepository ruleRepo, RuleEngine ruleEngine) {
        this.ruleRepo = ruleRepo;
        this.ruleEngine = ruleEngine;
    }

    @Scheduled(
            fixedDelayString = "${cmo2.alerts.eval-interval-ms:60000}",
            initialDelayString = "${cmo2.alerts.eval-initial-delay-ms:30000}")
    public void evaluateAll() {
        List<AlertRule> rules = ruleRepo.findByActiveTrue();
        if (rules.isEmpty()) {
            return;
        }
        log.debug("Evaluating {} active alert rule(s)", rules.size());
        for (AlertRule rule : rules) {
            TenantContext.set(rule.getTenantId(), null);
            try {
                ruleEngine.evaluate(rule);
            } catch (Exception e) {
                log.warn("Alert rule {} evaluation failed: {}", rule.getId(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }
}
