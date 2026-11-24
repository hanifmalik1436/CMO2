package com.cmo2.studio.domain;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Raw material for a customer's content: a transcript, notes, an article, a voice recording's
 * transcript, an uploaded file's text or a web page's text. Analysed for ideas in the background
 * as soon as it's added.
 */
@Entity
@Table(name = "content_sources")
@Getter
@Setter
public class ContentSource extends BaseTenantEntity {

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(nullable = false, length = 200)
    private String title;

    public static final String PENDING = "PENDING";
    public static final String ANALYZING = "ANALYZING";
    public static final String READY = "READY";
    public static final String TOO_SHORT = "TOO_SHORT";
    public static final String FAILED = "FAILED";

    /** TRANSCRIPT, NOTES, ARTICLE, RECORDING, UPLOAD or LINK. */
    @Column(nullable = false, length = 16)
    private String kind;

    /** Where the idea search stands: PENDING, ANALYZING, READY, TOO_SHORT or FAILED. */
    @Column(nullable = false, length = 16)
    private String status = READY;

    @Column(name = "status_note", length = 500)
    private String statusNote;

    @Column(nullable = false)
    private boolean starred;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String body;

    @Column(name = "analyzed_at")
    private Instant analyzedAt;

    /** When a batch was last generated from it. */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /** The web page it was read from (LINK). */
    @Column(name = "origin_url", length = 2048)
    private String originUrl;

    /** The uploaded file's name (UPLOAD, RECORDING). */
    @Column(name = "origin_file", length = 255)
    private String originFile;
}
