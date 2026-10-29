package com.cmo2.tenancy.controller;

import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.tenancy.domain.Tenant;
import com.cmo2.tenancy.repository.TenantRepository;
import com.cmo2.tenancy.service.TenantExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Read-only view of the caller's own tenant (plan, status, trial). Resolved from
 * the JWT-derived {@link TenantContext}; a tenant can only ever see itself.
 */
@RestController
@RequestMapping("/api/tenant")
@Tag(name = "Tenant")
public class TenantController {

    private final TenantRepository tenantRepository;
    private final TenantExportService exportService;

    public TenantController(TenantRepository tenantRepository, TenantExportService exportService) {
        this.tenantRepository = tenantRepository;
        this.exportService = exportService;
    }

    public record TenantResponse(Long id, String name, String status, String planCode, Instant trialEndsAt) {}

    @GetMapping("/current")
    @Transactional(readOnly = true)
    @Operation(summary = "Get the current tenant's profile")
    public TenantResponse current() {
        Long tenantId = TenantContext.requireTenantId();
        Tenant t = tenantRepository.findById(tenantId)
                .orElseThrow(() -> ApiException.notFound("Tenant not found"));
        return new TenantResponse(t.getId(), t.getName(), t.getStatus().name(),
                t.getPlanCode().name(), t.getTrialEndsAt());
    }

    @GetMapping("/export")
    @Operation(summary = "Export all of this tenant's data as a JSON bundle (GDPR groundwork)")
    public ResponseEntity<Map<String, Object>> export() {
        Map<String, Object> bundle = exportService.export();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"cmo2-data-export.json\"")
                .body(bundle);
    }
}
