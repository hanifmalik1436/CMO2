package com.cmo2.integrations.controller;

import com.cmo2.integrations.dto.IntegrationDtos.ConnectRequest;
import com.cmo2.integrations.dto.IntegrationDtos.ConnectionResponse;
import com.cmo2.integrations.dto.IntegrationDtos.ReconnectRequest;
import com.cmo2.integrations.dto.IntegrationDtos.SyncJobResponse;
import com.cmo2.integrations.dto.IntegrationDtos.SyncTriggerResponse;
import com.cmo2.integrations.service.ConnectionService;
import com.cmo2.integrations.service.ConnectorRegistry;
import com.cmo2.integrations.service.ConnectorRegistry.PlatformInfo;
import com.cmo2.integrations.service.SyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@Tag(name = "Integrations")
public class IntegrationsController {

    private final ConnectionService connectionService;
    private final SyncService syncService;
    private final ConnectorRegistry connectorRegistry;

    public IntegrationsController(ConnectionService connectionService, SyncService syncService,
                                 ConnectorRegistry connectorRegistry) {
        this.connectionService = connectionService;
        this.syncService = syncService;
        this.connectorRegistry = connectorRegistry;
    }

    @GetMapping("/integrations/platforms")
    @Operation(summary = "Catalog of integratable platforms (GoHighLevel, provisioned via signup) — which are connectable and accept deployed assets")
    public List<PlatformInfo> platforms() {
        return connectorRegistry.catalog();
    }

    @PostMapping("/companies/{companyId}/integrations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Connect a platform for a company (encrypts credentials, tests the connection)")
    public ConnectionResponse connect(@PathVariable Long companyId, @Valid @RequestBody ConnectRequest request) {
        return connectionService.connect(companyId, request);
    }

    @GetMapping("/companies/{companyId}/integrations")
    @Operation(summary = "List a company's platform connections (credentials masked)")
    public List<ConnectionResponse> list(@PathVariable Long companyId) {
        return connectionService.list(companyId);
    }

    @PostMapping("/integrations/{connectionId}/test")
    @Operation(summary = "Re-test a connection and refresh its traffic-light health")
    public ConnectionResponse test(@PathVariable Long connectionId) {
        return connectionService.test(connectionId);
    }

    @PostMapping("/integrations/{connectionId}/reconnect")
    @Operation(summary = "Rotate the stored credentials and re-test")
    public ConnectionResponse reconnect(@PathVariable Long connectionId, @Valid @RequestBody ReconnectRequest request) {
        return connectionService.reconnect(connectionId, request);
    }

    @PostMapping("/integrations/{connectionId}/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Run a sync now (pull KPIs into the dashboard)")
    public SyncTriggerResponse sync(@PathVariable Long connectionId) {
        return syncService.triggerManualSync(connectionId);
    }

    @GetMapping("/integrations/{connectionId}/sync-jobs")
    @Operation(summary = "Recent sync logs for a connection")
    public List<SyncJobResponse> syncJobs(@PathVariable Long connectionId) {
        return syncService.recentJobs(connectionId);
    }

    @DeleteMapping("/integrations/{connectionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Disconnect a platform")
    public void disconnect(@PathVariable Long connectionId) {
        connectionService.disconnect(connectionId);
    }
}
