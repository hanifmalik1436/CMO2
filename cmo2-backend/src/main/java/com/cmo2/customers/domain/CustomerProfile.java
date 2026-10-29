package com.cmo2.customers.domain;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * What CMO² knows about one customer beyond the company row. Offer, audience, banned topics and
 * notes are written by the owner; the rest is copied from the customer's GHL sub-account on each
 * sync, so it is the customer's own description of themselves and is treated as data.
 */
@Entity
@Table(name = "customer_profiles")
@Getter
@Setter
public class CustomerProfile extends BaseTenantEntity {

    @Column(name = "company_id", nullable = false, unique = true)
    private Long companyId;

    @Column(length = 1000)
    private String offer;

    @Column(length = 1000)
    private String audience;

    @Column(name = "banned_topics", length = 1000)
    private String bannedTopics;

    @Column(columnDefinition = "LONGTEXT")
    private String notes;

    @Column(name = "ghl_business_name", length = 160)
    private String ghlBusinessName;

    @Column(length = 120)
    private String city;

    @Column(length = 80)
    private String country;

    @Column(length = 40)
    private String phone;

    @Column(length = 160)
    private String email;

    /** Comma-separated channel names, e.g. "facebook,instagram". */
    @Column(name = "social_channels", length = 500)
    private String socialChannels;

    @Column(name = "facts_synced_at")
    private Instant factsSyncedAt;
}
