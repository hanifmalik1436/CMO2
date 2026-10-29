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

/** A per-metric threshold rule that fires alerts to configured channels. */
@Entity
@Table(name = "alert_rules")
@Getter
@Setter
public class AlertRule extends BaseTenantEntity {

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(nullable = false, length = 48)
    private String metric;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Operator operator;

    @Column(nullable = false)
    private double threshold;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity = Severity.WARNING;

    @Lob
    @Column(name = "channels_json")
    private String channelsJson; // JSON array of {type, target}

    @Column(nullable = false)
    private boolean active = true;
}
