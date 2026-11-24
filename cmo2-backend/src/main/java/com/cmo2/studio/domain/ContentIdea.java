package com.cmo2.studio.domain;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** An angle the strategy AI found in a source. */
@Entity
@Table(name = "content_ideas")
@Getter
@Setter
public class ContentIdea extends BaseTenantEntity {

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    /** INSIGHT, STORY, TIP, CONTRARIAN, HOW_TO or STAT. */
    @Column(name = "idea_type", nullable = false, length = 16)
    private String ideaType;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(length = 1000)
    private String summary;

    @Column(name = "hooks_json", columnDefinition = "LONGTEXT")
    private String hooksJson;

    @Column(length = 1000)
    private String quote;

    /** How strong the hook is for this customer's audience, 0 to 100; null for ideas found before scoring. */
    @Column(name = "virality_score")
    private Integer viralityScore;

    /** Why, naming the hook pattern (curiosity gap, contrarian, specific number…). */
    @Column(name = "virality_reason", length = 300)
    private String viralityReason;

    @Column(nullable = false)
    private int position;
}
