package com.cmo2.integrations.service;

import com.cmo2.integrations.domain.ConnectionStatus;
import com.cmo2.integrations.domain.PlatformConnection;
import com.cmo2.integrations.domain.SyncType;
import com.cmo2.integrations.repository.PlatformConnectionRepository;
import com.cmo2.shared.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Background "zero-click refresh": periodically syncs every CONNECTED platform across
 * all tenants. Runs with NO tenant context for the sweep query (so it sees every tenant),
 * then binds each connection's tenant before syncing it — keeping tenant scoping intact.
 *
 * <p>This is what makes the Phase 3 exit criterion true: "walk away for 30 minutes; data
 * updates itself."
 */
@Component
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final PlatformConnectionRepository connectionRepo;
    private final SyncService syncService;
    private final Duration liveSyncEvery;

    public SyncScheduler(PlatformConnectionRepository connectionRepo, SyncService syncService,
                         @Value("${cmo2.integrations.gohighlevel.live-sync-minutes:15}") long liveSyncMinutes) {
        this.connectionRepo = connectionRepo;
        this.syncService = syncService;
        this.liveSyncEvery = Duration.ofMinutes(Math.max(1, liveSyncMinutes));
    }

    /**
     * Agency-linked sub-accounts are always live and one sync makes a few dozen GHL calls, so they
     * sync every {@code live-sync-minutes} rather than on every sweep. Demo connections keep the
     * sweep's pace.
     */
    boolean due(PlatformConnection conn, Instant now) {
        if (!ConnectionService.AUTH_MODE_GHL_AGENCY.equals(conn.getAuthMode()) || conn.getLastSyncAt() == null) {
            return true;
        }
        return !conn.getLastSyncAt().plus(liveSyncEvery).isAfter(now);
    }

    @Scheduled(
            fixedDelayString = "${cmo2.integrations.sync-interval-ms:60000}",
            initialDelayString = "${cmo2.integrations.sync-initial-delay-ms:20000}")
    public void runScheduledSyncs() {
        List<PlatformConnection> connections = connectionRepo.findByStatus(ConnectionStatus.CONNECTED);
        if (connections.isEmpty()) {
            return;
        }
        log.debug("Scheduled sync sweep over {} connection(s)", connections.size());
        Instant now = Instant.now();
        for (PlatformConnection conn : connections) {
            if (!due(conn, now)) {
                continue;
            }
            TenantContext.set(conn.getTenantId(), null);
            try {
                syncService.syncConnection(conn.getId(), SyncType.SCHEDULED);
            } catch (Exception e) {
                log.warn("Scheduled sync errored for connection {}: {}", conn.getId(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }
}
