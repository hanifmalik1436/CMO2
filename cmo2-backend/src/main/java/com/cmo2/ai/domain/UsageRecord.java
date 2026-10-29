package com.cmo2.ai.domain;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Per-tenant usage counter (AI tokens, etc.) rolled up by billing period (YYYY-MM). */
@Entity
@Table(name = "usage_records")
@Getter
@Setter
public class UsageRecord extends BaseTenantEntity {

    public static final String METRIC_AI_TOKENS = "ai_tokens";

    @Column(nullable = false, length = 48)
    private String metric;

    @Column(nullable = false)
    private long quantity;

    @Column(nullable = false, length = 7)
    private String period; // YYYY-MM
}
