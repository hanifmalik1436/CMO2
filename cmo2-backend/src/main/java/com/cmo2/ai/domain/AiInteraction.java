package com.cmo2.ai.domain;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One AI call: what was asked, what came back, and what it cost. */
@Entity
@Table(name = "ai_interactions")
@Getter
@Setter
public class AiInteraction extends BaseTenantEntity {

    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "iteration_id")
    private Long iterationId;

    @Column(length = 24)
    private String stage;

    @Column(nullable = false, length = 64)
    private String kind;

    @Column(nullable = false, length = 16)
    private String tier;

    @Column(length = 80)
    private String model;

    @Column(name = "prompt_text", columnDefinition = "LONGTEXT")
    private String promptText;

    @Column(name = "response_text", columnDefinition = "LONGTEXT")
    private String responseText;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;
}
