package com.cmo2.alerts.domain;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** A fired alert instance — the unit shown in the alert center. */
@Entity
@Table(name = "alerts")
@Getter
@Setter
public class Alert extends BaseTenantEntity {

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "rule_id")
    private Long ruleId;

    @Column(nullable = false, length = 48)
    private String metric;

    // Column is named metric_value: "value" is a reserved word in some engines (e.g. H2).
    @Column(name = "metric_value", nullable = false)
    private double value;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AlertStatus status = AlertStatus.FIRED;

    @Column(length = 1000)
    private String message;

    @Lob
    @Column(columnDefinition = "LONGTEXT", name = "dispatched_json")
    private String dispatchedJson;

    @Column(name = "fired_at")
    private Instant firedAt;

    @Column(name = "acked_by")
    private Long ackedBy;

    @Column(name = "acked_at")
    private Instant ackedAt;
}
