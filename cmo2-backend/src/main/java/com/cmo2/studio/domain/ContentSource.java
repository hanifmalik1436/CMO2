package com.cmo2.studio.domain;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Raw material for a customer's content: a transcript, notes or an article. */
@Entity
@Table(name = "content_sources")
@Getter
@Setter
public class ContentSource extends BaseTenantEntity {

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(nullable = false, length = 200)
    private String title;

    /** TRANSCRIPT, NOTES or ARTICLE. */
    @Column(nullable = false, length = 16)
    private String kind;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String body;

    @Column(name = "analyzed_at")
    private Instant analyzedAt;
}
