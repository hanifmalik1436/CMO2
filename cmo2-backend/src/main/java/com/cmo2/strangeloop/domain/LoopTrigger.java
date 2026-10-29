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

/**
 * A rule that launches loop cycles. trigger_type SCHEDULE is a customer's cadence (one per
 * customer, mirrored by a Quartz trigger); KPI-condition triggers are for later phases.
 */
@Entity
@Table(name = "loop_triggers")
@Getter
@Setter
public class LoopTrigger extends BaseTenantEntity {

    public static final String SCHEDULE = "SCHEDULE";

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "trigger_type", nullable = false, length = 24)
    private String triggerType;

    /** For a SCHEDULE trigger: how often the customer's cycle runs. */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Cadence cadence;

    /** HH:mm in the customer's timezone. */
    @Column(name = "time_of_day", length = 5)
    private String timeOfDay;

    /** 1 = Monday … 7 = Sunday (WEEKLY). */
    @Column(name = "day_of_week")
    private Integer dayOfWeek;

    /** 1 … 28 (MONTHLY), so every month has the day. */
    @Column(name = "day_of_month")
    private Integer dayOfMonth;

    @Column(name = "stage_no")
    private Integer stageNo;

    @Lob
    @Column(columnDefinition = "LONGTEXT", name = "conditions_json")
    private String conditionsJson;

    @Lob
    @Column(columnDefinition = "LONGTEXT", name = "actions_json")
    private String actionsJson;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
