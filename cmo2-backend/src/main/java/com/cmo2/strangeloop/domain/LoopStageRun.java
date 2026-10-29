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

/**
 * A single stage execution within an iteration. Persisting these lets a crashed
 * cycle resume from the first not-yet-COMPLETED stage instead of restarting.
 */
@Entity
@Table(name = "loop_stage_runs")
@Getter
@Setter
public class LoopStageRun extends BaseTenantEntity {

    @Column(name = "iteration_id", nullable = false)
    private Long iterationId;

    @Column(name = "stage_no", nullable = false)
    private int stageNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage_name", nullable = false, length = 32)
    private StageName stageName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private StageStatus status = StageStatus.PENDING;

    @Column(name = "progress_pct", nullable = false)
    private int progressPct = 0;

    @Lob
    @Column(columnDefinition = "LONGTEXT", name = "data_json")
    private String dataJson;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
