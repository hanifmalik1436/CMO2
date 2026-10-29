package com.cmo2.integrations.service;

import com.cmo2.integrations.domain.PlatformConnection;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SyncSchedulerTest {

    private final SyncScheduler scheduler = new SyncScheduler(null, null, 15);
    private final Instant now = Instant.parse("2026-10-28T12:00:00Z");

    private PlatformConnection conn(String authMode, Duration sinceLastSync) {
        PlatformConnection c = new PlatformConnection();
        c.setAuthMode(authMode);
        c.setLastSyncAt(sinceLastSync == null ? null : now.minus(sinceLastSync));
        return c;
    }

    @Test
    void liveSubAccountsSyncEveryFifteenMinutes() {
        assertThat(scheduler.due(conn(ConnectionService.AUTH_MODE_GHL_AGENCY, Duration.ofMinutes(5)), now)).isFalse();
        assertThat(scheduler.due(conn(ConnectionService.AUTH_MODE_GHL_AGENCY, Duration.ofMinutes(15)), now)).isTrue();
        assertThat(scheduler.due(conn(ConnectionService.AUTH_MODE_GHL_AGENCY, null), now)).isTrue();
    }

    @Test
    void demoConnectionsKeepTheSweepsPace() {
        assertThat(scheduler.due(conn("API_KEY", Duration.ofSeconds(30)), now)).isTrue();
    }
}
