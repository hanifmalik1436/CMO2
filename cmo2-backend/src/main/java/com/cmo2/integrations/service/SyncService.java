package com.cmo2.integrations.service;

import com.cmo2.analytics.service.KpiIngestor;
import com.cmo2.integrations.core.Health;
import com.cmo2.integrations.core.Timezones;
import com.cmo2.integrations.core.SyncModels.SyncRequest;
import com.cmo2.integrations.core.SyncModels.SyncResult;
import com.cmo2.integrations.domain.PlatformConnection;
import com.cmo2.integrations.domain.SyncJob;
import com.cmo2.integrations.domain.SyncStatus;
import com.cmo2.integrations.domain.SyncType;
import com.cmo2.integrations.events.ConnectionHealthChangedEvent;
import com.cmo2.integrations.events.CustomerFactsSyncedEvent;
import com.cmo2.integrations.dto.IntegrationDtos.SyncJobResponse;
import com.cmo2.integrations.dto.IntegrationDtos.SyncTriggerResponse;
import com.cmo2.integrations.repository.PlatformConnectionRepository;
import com.cmo2.integrations.repository.SyncJobRepository;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.tenancy.TenantGuard;
import com.cmo2.tenancy.domain.Company;
import com.cmo2.tenancy.repository.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * Runs platform syncs: pull KPIs via the connector, ingest into kpi_snapshots (and the
 * customer's daily rollups, in their own timezone), and
 * record a {@link SyncJob} log entry. Used both by the manual "Sync now" endpoint and
 * the background {@link SyncScheduler}.
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final PlatformConnectionRepository connectionRepo;
    private final SyncJobRepository syncJobRepo;
    private final ConnectorRegistry connectors;
    private final ConnectionService connectionService;
    private final KpiIngestor kpiIngestor;
    private final ApplicationEventPublisher events;
    private final CompanyRepository companyRepo;

    public SyncService(PlatformConnectionRepository connectionRepo,
                       SyncJobRepository syncJobRepo,
                       ConnectorRegistry connectors,
                       ConnectionService connectionService,
                       KpiIngestor kpiIngestor,
                       ApplicationEventPublisher events,
                       CompanyRepository companyRepo) {
        this.connectionRepo = connectionRepo;
        this.syncJobRepo = syncJobRepo;
        this.connectors = connectors;
        this.connectionService = connectionService;
        this.kpiIngestor = kpiIngestor;
        this.events = events;
        this.companyRepo = companyRepo;
    }

    /** Announces only transitions between down and up, so the team isn't pinged on every sync. */
    private void announceHealthChange(PlatformConnection conn, Health before) {
        boolean wasDown = before == Health.RED;
        boolean isDown = conn.getHealth() == Health.RED;
        if (wasDown != isDown) {
            events.publishEvent(new ConnectionHealthChangedEvent(conn.getTenantId(), conn.getCompanyId(),
                    conn.getPlatform().displayName, !isDown, conn.getLastError()));
        }
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST')")
    public SyncTriggerResponse triggerManualSync(Long connectionId) {
        return syncConnection(connectionId, SyncType.MANUAL);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public List<SyncJobResponse> recentJobs(Long connectionId) {
        // Confirm the connection is in this tenant before exposing its logs.
        TenantGuard.owned(connectionRepo.findById(connectionId))
                .orElseThrow(() -> ApiException.notFound("Connection not found"));
        return syncJobRepo.findByConnectionIdOrderByStartedAtDesc(connectionId, PageRequest.of(0, 25)).stream()
                .map(SyncJobResponse::of)
                .toList();
    }

    /**
     * Core sync. The caller must have a tenant bound in context (REST request, or set by
     * the scheduler). Exceptions are caught and recorded so the FAILED job persists.
     */
    @Transactional
    public SyncTriggerResponse syncConnection(Long connectionId, SyncType type) {
        PlatformConnection conn = TenantGuard.owned(connectionRepo.findById(connectionId))
                .orElseThrow(() -> ApiException.notFound("Connection not found"));
        Health before = conn.getHealth();

        SyncJob job = new SyncJob();
        job.setConnectionId(connectionId);
        job.setType(type);
        job.setStatus(SyncStatus.RUNNING);
        job.setStartedAt(Instant.now());
        job = syncJobRepo.save(job);

        try {
            String knownTimezone = companyRepo.findById(conn.getCompanyId())
                    .map(Company::getTimezone).orElse(null);
            SyncResult result = connectors.require(conn.getPlatform())
                    .pullData(new SyncRequest(conn.getCompanyId(), connectionService.credentials(conn), knownTimezone));

            Instant capturedAt = Instant.now();
            // The customer's day: the timezone the platform just reported, else the last known one, else UTC.
            ZoneId zone = Timezones.firstValid(result.facts() == null ? null : result.facts().timezone(), knownTimezone);
            int ingested = result.kpis().isEmpty() ? 0
                    : kpiIngestor.ingest(conn.getCompanyId(), conn.getPlatform().name(), result.kpis(), capturedAt, zone);
            if (result.facts() != null) {
                events.publishEvent(new CustomerFactsSyncedEvent(conn.getTenantId(), conn.getCompanyId(), result.facts()));
            }

            connectionService.applyHealth(conn, result.health());
            conn.setLastSyncAt(capturedAt);
            connectionRepo.save(conn);
            announceHealthChange(conn, before);

            boolean ok = result.health().status() != Health.RED;
            job.setStatus(ok ? SyncStatus.SUCCESS : SyncStatus.FAILED);
            job.setKpisIngested(ingested);
            job.setFinishedAt(Instant.now());
            if (!ok) job.setErrorMessage(result.message());
            syncJobRepo.save(job);

            return new SyncTriggerResponse(connectionId, conn.getHealth().name(), ingested, result.message());

        } catch (Exception e) {
            log.warn("Sync failed for connection {}: {}", connectionId, e.getMessage());
            conn.setHealth(Health.RED);
            conn.setLastError(e.getMessage());
            connectionRepo.save(conn);
            announceHealthChange(conn, before);

            job.setStatus(SyncStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setFinishedAt(Instant.now());
            syncJobRepo.save(job);
            return new SyncTriggerResponse(connectionId, Health.RED.name(), 0, "Sync failed: " + e.getMessage());
        }
    }
}
