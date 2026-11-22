package com.cmo2.customers.domain;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One version of a customer's brand canon: the owner-approved description of the business and
 * its brand rules. Only the APPROVED version reaches the AI, in full and at the start of every
 * call, so years of cycle history can't outweigh who the customer is. Nothing changes it
 * automatically: a new version is a DRAFT until the owner or an admin approves it.
 */
@Entity
@Table(name = "customer_canon_versions",
        uniqueConstraints = @UniqueConstraint(name = "uk_canon_version", columnNames = {"company_id", "version_no"}))
@Getter
@Setter
public class CustomerCanon extends BaseTenantEntity {

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    /** Given on approval (1, 2, 3…); null while the version is a draft. */
    @Column(name = "version_no")
    private Integer versionNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CanonStatus status;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "change_note", length = 500)
    private String changeNote;

    @Column(name = "proposed_by_user_id")
    private Long proposedByUserId;

    @Column(name = "approved_by_user_id")
    private Long approvedByUserId;

    @Column(name = "approved_at")
    private Instant approvedAt;
}
