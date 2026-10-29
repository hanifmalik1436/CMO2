package com.cmo2.strangeloop.domain;

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

/** One run of the 5-stage Strange Loop for a company. */
@Entity
@Table(name = "loop_iterations")
@Getter
@Setter
public class LoopIteration extends BaseTenantEntity {

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "iteration_no", nullable = false)
    private int iterationNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private IterationStatus status = IterationStatus.RUNNING;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 24)
    private TriggerType triggerType = TriggerType.MANUAL;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Lob
    @Column(columnDefinition = "LONGTEXT", name = "insights_json")
    private String insightsJson;

    @Lob
    @Column(columnDefinition = "LONGTEXT", name = "gaps_json")
    private String gapsJson;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;
}
