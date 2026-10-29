package com.cmo2.strangeloop.domain;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A configurable condition→action rule that can launch loop cycles
 * (schedule-based or KPI-condition-based). Persisted for Phase 2; the condition
 * evaluation engine is fleshed out in later phases.
 */
@Entity
@Table(name = "loop_triggers")
@Getter
@Setter
public class LoopTrigger extends BaseTenantEntity {

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "trigger_type", nullable = false, length = 24)
    private String triggerType;

    @Column(name = "stage_no")
    private Integer stageNo;

    @Lob
    @Column(name = "conditions_json")
    private String conditionsJson;

    @Lob
    @Column(name = "actions_json")
    private String actionsJson;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
