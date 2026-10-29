package com.cmo2.alerts.service;

import com.cmo2.alerts.domain.Alert;
import com.cmo2.alerts.domain.AlertRule;
import com.cmo2.alerts.domain.AlertStatus;
import com.cmo2.alerts.domain.Operator;
import com.cmo2.alerts.domain.Severity;
import com.cmo2.alerts.dto.AlertDtos.AlertResponse;
import com.cmo2.alerts.dto.AlertDtos.CreateRuleRequest;
import com.cmo2.alerts.dto.AlertDtos.RuleResponse;
import com.cmo2.alerts.repository.AlertRepository;
import com.cmo2.alerts.repository.AlertRuleRepository;
import com.cmo2.analytics.service.KpiIngestor;
import com.cmo2.integrations.core.SyncModels.KpiPoint;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.shared.tenancy.TenantGuard;
import com.cmo2.tenancy.repository.CompanyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Manages alert rules and the alert center, plus a "simulate" hook that ingests a KPI
 * spike and evaluates rules immediately — the demonstrable path for the Phase 6 exit
 * criterion (a CPL spike fires email + Slack alerts).
 */
@Service
public class AlertService {

    private final AlertRuleRepository ruleRepo;
    private final AlertRepository alertRepo;
    private final CompanyRepository companyRepo;
    private final RuleEngine ruleEngine;
    private final KpiIngestor kpiIngestor;
    private final ObjectMapper objectMapper;

    public AlertService(AlertRuleRepository ruleRepo, AlertRepository alertRepo, CompanyRepository companyRepo,
                        RuleEngine ruleEngine, KpiIngestor kpiIngestor, ObjectMapper objectMapper) {
        this.ruleRepo = ruleRepo;
        this.alertRepo = alertRepo;
        this.companyRepo = companyRepo;
        this.ruleEngine = ruleEngine;
        this.kpiIngestor = kpiIngestor;
        this.objectMapper = objectMapper;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public RuleResponse createRule(Long companyId, CreateRuleRequest req) {
        TenantGuard.owned(companyRepo.findById(companyId))
                .orElseThrow(() -> ApiException.notFound("Company not found"));

        AlertRule rule = new AlertRule();
        rule.setCompanyId(companyId);
        rule.setMetric(req.metric().trim());
        rule.setOperator(parseOperator(req.operator()));
        rule.setThreshold(req.threshold());
        rule.setSeverity(parseSeverity(req.severity()));
        rule.setChannelsJson(toJson(req.channels()));
        rule.setActive(true);
        return RuleResponse.of(ruleRepo.save(rule));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public List<RuleResponse> listRules(Long companyId) {
        return ruleRepo.findByCompanyIdOrderByCreatedAtDesc(companyId).stream().map(RuleResponse::of).toList();
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public void deleteRule(Long ruleId) {
        AlertRule rule = TenantGuard.owned(ruleRepo.findById(ruleId))
                .orElseThrow(() -> ApiException.notFound("Rule not found"));
        ruleRepo.delete(rule);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public List<AlertResponse> listAlerts(int limit) {
        return alertRepo.findByOrderByFiredAtDesc(PageRequest.of(0, Math.min(Math.max(limit, 1), 200)))
                .stream().map(AlertResponse::of).toList();
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST')")
    public AlertResponse acknowledge(Long alertId) {
        Alert alert = TenantGuard.owned(alertRepo.findById(alertId))
                .orElseThrow(() -> ApiException.notFound("Alert not found"));
        alert.setStatus(AlertStatus.ACKED);
        alert.setAckedBy(TenantContext.getUserId());
        alert.setAckedAt(Instant.now());
        return AlertResponse.of(alertRepo.save(alert));
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST')")
    public List<AlertResponse> simulate(Long companyId, String metric, double value) {
        TenantGuard.owned(companyRepo.findById(companyId))
                .orElseThrow(() -> ApiException.notFound("Company not found"));

        kpiIngestor.ingest(companyId, "SIMULATION", List.of(new KpiPoint(metric, value)), Instant.now());
        for (AlertRule rule : ruleRepo.findByCompanyIdOrderByCreatedAtDesc(companyId)) {
            if (rule.isActive() && rule.getMetric().equals(metric)) {
                ruleEngine.evaluate(rule);
            }
        }
        return listAlerts(20);
    }

    private Operator parseOperator(String raw) {
        try {
            return Operator.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            throw ApiException.badRequest("Invalid operator: " + raw);
        }
    }

    private Severity parseSeverity(String raw) {
        if (raw == null || raw.isBlank()) return Severity.WARNING;
        try {
            return Severity.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            throw ApiException.badRequest("Invalid severity: " + raw);
        }
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
