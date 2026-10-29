package com.cmo2.strangeloop.experiments;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** A hypothesis Stage 5 proposed, the action that tests it, and what happened. */
@Entity
@Table(name = "experiments")
@Getter
@Setter
public class Experiment extends BaseTenantEntity {

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "iteration_id", nullable = false)
    private Long iterationId;

    @Column(nullable = false, length = 500)
    private String hypothesis;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 32)
    private ExperimentAction actionType;

    @Column(length = 32)
    private String capability;

    @Column(name = "risk_tier", nullable = false, length = 24)
    private String riskTier;

    @Column(name = "params_json", columnDefinition = "LONGTEXT")
    private String paramsJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExperimentStatus status = ExperimentStatus.PROPOSED;

    @Column(nullable = false, length = 48)
    private String metric;

    /** up or down. */
    @Column(name = "expected_direction", nullable = false, length = 8)
    private String expectedDirection;

    @Column(name = "baseline_value")
    private Double baselineValue;

    @Column(name = "result_value")
    private Double resultValue;

    /** SUPPORTED, NOT_SUPPORTED or INCONCLUSIVE. */
    @Column(length = 16)
    private String outcome;

    @Column(name = "result_summary", length = 1000)
    private String resultSummary;

    @Column(name = "asset_id")
    private Long assetId;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note", length = 1000)
    private String decisionNote;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "measured_at")
    private Instant measuredAt;
}
