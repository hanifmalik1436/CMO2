package com.cmo2.alerts.dto;

import com.cmo2.alerts.domain.Alert;
import com.cmo2.alerts.domain.AlertRule;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/** Request/response DTOs for the Alerts module. */
public final class AlertDtos {

    private AlertDtos() {}

    /** A delivery channel spec ({@code type} = EMAIL|SMS|SLACK|IN_APP). Also the JSON shape stored on a rule. */
    public record ChannelSpec(String type, String target) {}

    public record CreateRuleRequest(
            @NotBlank String metric,
            @NotBlank String operator,     // GT, LT, GTE, LTE
            @NotNull Double threshold,
            String severity,               // INFO, WARNING, CRITICAL
            List<ChannelSpec> channels
    ) {}

    public record RuleResponse(
            Long id, Long companyId, String metric, String operator, double threshold,
            String severity, String channelsJson, boolean active, Instant createdAt
    ) {
        public static RuleResponse of(AlertRule r) {
            return new RuleResponse(r.getId(), r.getCompanyId(), r.getMetric(), r.getOperator().name(),
                    r.getThreshold(), r.getSeverity().name(), r.getChannelsJson(), r.isActive(), r.getCreatedAt());
        }
    }

    public record AlertResponse(
            Long id, Long companyId, Long ruleId, String metric, double value, String severity,
            String status, String message, String dispatchedJson, Instant firedAt, Instant ackedAt
    ) {
        public static AlertResponse of(Alert a) {
            return new AlertResponse(a.getId(), a.getCompanyId(), a.getRuleId(), a.getMetric(), a.getValue(),
                    a.getSeverity().name(), a.getStatus().name(), a.getMessage(), a.getDispatchedJson(),
                    a.getFiredAt(), a.getAckedAt());
        }
    }

    public record SimulateRequest(@NotBlank String metric, @NotNull Double value) {}
}
