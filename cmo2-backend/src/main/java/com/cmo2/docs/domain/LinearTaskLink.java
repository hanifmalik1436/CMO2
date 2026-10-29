package com.cmo2.docs.domain;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** The Linear issue CMO² opened for one of a customer's pending tasks. */
@Entity
@Table(name = "linear_task_links",
        uniqueConstraints = @UniqueConstraint(name = "uk_linear_task", columnNames = {"company_id", "task_key"}))
@Getter
@Setter
public class LinearTaskLink extends BaseTenantEntity {

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    /** Stable id of the task inside CMO², e.g. "asset:42:review". */
    @Column(name = "task_key", nullable = false, length = 120)
    private String taskKey;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "linear_issue_id", nullable = false, length = 64)
    private String linearIssueId;

    @Column(length = 32)
    private String identifier;

    @Column(length = 512)
    private String url;

    /** Last Linear workflow state type seen (unstarted, started, completed, canceled…). */
    @Column(name = "state_type", length = 24)
    private String stateType;

    /** True when CMO² closed it because the task was done in CMO² (so it isn't reported as the owner's close). */
    @Column(name = "closed_by_cmo2", nullable = false)
    private boolean closedByCmo2;

    /** Comments after this are new notes from the owner. */
    @Column(name = "last_read_at")
    private Instant lastReadAt;
}
