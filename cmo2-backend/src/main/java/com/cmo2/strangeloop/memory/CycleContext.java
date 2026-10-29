package com.cmo2.strangeloop.memory;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * The record of one customer cycle, written at Close and loaded at the next Boot. The JSON columns
 * hold lists for display; {@code compressedContext} is the compact text the next cycle starts from.
 */
@Entity
@Table(name = "cycle_context")
@Getter
@Setter
public class CycleContext extends BaseTenantEntity {

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "iteration_id", nullable = false, unique = true)
    private Long iterationId;

    @Column(name = "iteration_no", nullable = false)
    private int iterationNo;

    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;

    @Column(name = "decisions_json", columnDefinition = "LONGTEXT")
    private String decisionsJson;

    @Column(name = "tasks_completed_json", columnDefinition = "LONGTEXT")
    private String tasksCompletedJson;

    @Column(name = "tasks_pending_json", columnDefinition = "LONGTEXT")
    private String tasksPendingJson;

    @Column(name = "content_produced_json", columnDefinition = "LONGTEXT")
    private String contentProducedJson;

    @Column(name = "operator_notes_json", columnDefinition = "LONGTEXT")
    private String operatorNotesJson;

    @Column(columnDefinition = "LONGTEXT")
    private String summary;

    @Column(name = "compressed_context", columnDefinition = "LONGTEXT")
    private String compressedContext;

    /** Tool → {pageId, url} of the pages written for this cycle. */
    @Column(name = "published_refs_json", columnDefinition = "LONGTEXT")
    private String publishedRefsJson;

    /** Exactly what CMO² wrote to those pages, to tell the owner's later edits apart. */
    @Column(name = "published_body", columnDefinition = "LONGTEXT")
    private String publishedBody;
}
