package com.cmo2.studio.domain;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** One "generate" request: material × formats × drafts each, written in the background. */
@Entity
@Table(name = "content_batches")
@Getter
@Setter
public class ContentBatch extends BaseTenantEntity {

    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String PARTIAL = "PARTIAL";
    public static final String FAILED = "FAILED";

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(nullable = false, length = 16)
    private String status = RUNNING;

    @Column(name = "total_pieces", nullable = false)
    private int totalPieces;

    @Column(nullable = false)
    private int completed;

    @Column(nullable = false)
    private int failed;

    @Column(name = "request_json", columnDefinition = "LONGTEXT")
    private String requestJson;

    @Column(length = 1000)
    private String note;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
