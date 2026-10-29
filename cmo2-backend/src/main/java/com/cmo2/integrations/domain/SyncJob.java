package com.cmo2.integrations.domain;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** One execution of a platform sync — the "View Logs" history per connection. */
@Entity
@Table(name = "sync_jobs")
@Getter
@Setter
public class SyncJob extends BaseTenantEntity {

    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SyncType type = SyncType.SCHEDULED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SyncStatus status = SyncStatus.RUNNING;

    @Column(name = "kpis_ingested", nullable = false)
    private int kpisIngested = 0;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;
}
