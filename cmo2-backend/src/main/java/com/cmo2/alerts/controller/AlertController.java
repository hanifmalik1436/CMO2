package com.cmo2.alerts.controller;

import com.cmo2.alerts.dto.AlertDtos.AlertResponse;
import com.cmo2.alerts.dto.AlertDtos.CreateRuleRequest;
import com.cmo2.alerts.dto.AlertDtos.RuleResponse;
import com.cmo2.alerts.dto.AlertDtos.SimulateRequest;
import com.cmo2.alerts.service.AlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@Tag(name = "Alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @PostMapping("/companies/{companyId}/alert-rules")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a threshold alert rule for a company")
    public RuleResponse createRule(@PathVariable Long companyId, @Valid @RequestBody CreateRuleRequest request) {
        return alertService.createRule(companyId, request);
    }

    @GetMapping("/companies/{companyId}/alert-rules")
    @Operation(summary = "List a company's alert rules")
    public List<RuleResponse> listRules(@PathVariable Long companyId) {
        return alertService.listRules(companyId);
    }

    @DeleteMapping("/alert-rules/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an alert rule")
    public void deleteRule(@PathVariable Long ruleId) {
        alertService.deleteRule(ruleId);
    }

    @GetMapping("/alerts")
    @Operation(summary = "Alert center — fired alerts, newest first")
    public List<AlertResponse> listAlerts(@RequestParam(defaultValue = "50") int limit) {
        return alertService.listAlerts(limit);
    }

    @PostMapping("/alerts/{alertId}/acknowledge")
    @Operation(summary = "Acknowledge an alert")
    public AlertResponse acknowledge(@PathVariable Long alertId) {
        return alertService.acknowledge(alertId);
    }

    @PostMapping("/companies/{companyId}/alerts/simulate")
    @Operation(summary = "Simulate a KPI spike and evaluate rules immediately (demo of the alert path)")
    public List<AlertResponse> simulate(@PathVariable Long companyId, @Valid @RequestBody SimulateRequest request) {
        return alertService.simulate(companyId, request.metric(), request.value());
    }
}
