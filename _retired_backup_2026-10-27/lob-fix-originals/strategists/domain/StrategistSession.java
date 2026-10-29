package com.cmo2.strategists.domain;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A working conversation with one strategist agent for a company. */
@Entity
@Table(name = "strategist_sessions")
@Getter
@Setter
public class StrategistSession extends BaseTenantEntity {

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "agent_code", nullable = false, length = 48)
    private String agentCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private SessionStatus status = SessionStatus.ACTIVE;

    @Lob
    @Column(name = "context_json")
    private String contextJson;

    @Lob
    @Column(name = "deliverables_json")
    private String deliverablesJson;
}
