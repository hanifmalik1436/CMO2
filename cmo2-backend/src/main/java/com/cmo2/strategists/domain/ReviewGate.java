package com.cmo2.strategists.domain;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A human approval checkpoint between strategist phases (RG-1 … RG-4). Approving a
 * gate releases that phase's deliverables into the company workspace.
 */
@Entity
@Table(name = "review_gates")
@Getter
@Setter
public class ReviewGate extends BaseTenantEntity {

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "gate_code", nullable = false, length = 8)
    private String gateCode; // RG-1 .. RG-4

    @Column(nullable = false)
    private int phase;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GateStatus status = GateStatus.PENDING;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(length = 1000)
    private String notes;
}
