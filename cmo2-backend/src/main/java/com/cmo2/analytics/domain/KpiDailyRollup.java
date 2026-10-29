package com.cmo2.analytics.domain;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One metric for one customer on one local day: the first reading ({@code openValue}) and the
 * latest ({@code value}). Daily figures are derived from running totals with it, and Stage 2
 * compares cycles over it.
 */
@Entity
@Table(name = "kpi_daily_rollups",
        uniqueConstraints = @UniqueConstraint(name = "uk_rollup_day",
                columnNames = {"company_id", "platform", "metric", "metric_date"}))
@Getter
@Setter
public class KpiDailyRollup extends BaseTenantEntity {

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(nullable = false, length = 32)
    private String platform;

    @Column(nullable = false, length = 48)
    private String metric;

    @Column(name = "metric_date", nullable = false)
    private LocalDate metricDate;

    @Column(name = "open_value", nullable = false)
    private double openValue;

    @Column(name = "metric_value", nullable = false)
    private double value;

    @Column(nullable = false)
    private int samples = 1;

    @Column(name = "last_captured_at", nullable = false)
    private Instant lastCapturedAt;
}
