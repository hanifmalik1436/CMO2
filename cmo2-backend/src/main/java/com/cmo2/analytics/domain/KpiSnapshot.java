package com.cmo2.analytics.domain;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A single KPI value captured for a company/platform at a point in time. Written by
 * the integrations sync (Phase 3); aggregated and charted by the analytics module (Phase 6);
 * consumed by Strange Loop Stage 2 learning.
 */
@Entity
@Table(name = "kpi_snapshots")
@Getter
@Setter
public class KpiSnapshot extends BaseTenantEntity {

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(nullable = false, length = 32)
    private String platform;

    @Column(nullable = false, length = 48)
    private String metric;

    // Column is named metric_value: "value" is a reserved word in some engines (e.g. H2).
    @Column(name = "metric_value", nullable = false)
    private double value;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;
}
