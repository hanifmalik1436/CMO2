package com.cmo2.tenancy.service;

import com.cmo2.auth.domain.User;
import com.cmo2.auth.repository.UserRepository;
import com.cmo2.integrations.domain.PlatformConnection;
import com.cmo2.integrations.repository.PlatformConnectionRepository;
import com.cmo2.shared.audit.AuditService;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.tenancy.domain.Company;
import com.cmo2.tenancy.domain.Tenant;
import com.cmo2.tenancy.repository.CompanyRepository;
import com.cmo2.tenancy.repository.TenantRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GDPR-groundwork data export: assembles a portable JSON bundle of the current tenant's data.
 * Secrets are never included (platform keys appear only as their masked last-4). Tenant
 * scoping is automatic (Hibernate filter); only the caller's own tenant can be exported.
 */
@Service
public class TenantExportService {

    private final TenantRepository tenants;
    private final UserRepository users;
    private final CompanyRepository companies;
    private final PlatformConnectionRepository connections;
    private final AuditService audit;

    public TenantExportService(TenantRepository tenants, UserRepository users,
                               CompanyRepository companies, PlatformConnectionRepository connections,
                               AuditService audit) {
        this.tenants = tenants;
        this.users = users;
        this.companies = companies;
        this.connections = connections;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public Map<String, Object> export() {
        Long tenantId = TenantContext.requireTenantId();
        Tenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> ApiException.notFound("Tenant not found"));

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("exportedAt", Instant.now().toString());
        bundle.put("schemaVersion", 1);
        bundle.put("tenant", tenantInfo(tenant));
        bundle.put("team", team(tenantId));
        bundle.put("companies", companies());
        bundle.put("connections", connections());

        audit.record("TENANT_DATA_EXPORTED", "Tenant", String.valueOf(tenantId),
                "Self-serve data export");
        return bundle;
    }

    private Map<String, Object> tenantInfo(Tenant t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("status", t.getStatus().name());
        m.put("plan", t.getPlanCode().name());
        m.put("trialEndsAt", t.getTrialEndsAt());
        m.put("currentPeriodEnd", t.getCurrentPeriodEnd());
        m.put("createdAt", t.getCreatedAt());
        return m;
    }

    private List<Map<String, Object>> team(Long tenantId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (User u : users.findByTenantIdOrderByCreatedAtAsc(tenantId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("email", u.getEmail());
            m.put("fullName", u.getFullName());
            m.put("status", u.getStatus().name());
            m.put("emailVerified", u.isEmailVerified());
            m.put("roles", u.getRoles().stream().map(r -> r.getName().name()).sorted().toList());
            m.put("lastLoginAt", u.getLastLoginAt());
            m.put("createdAt", u.getCreatedAt());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> companies() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Company c : companies.findAllByOrderByCreatedAtDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("industry", c.getIndustry());
            m.put("brandVoice", c.getBrandVoice());
            m.put("website", c.getWebsite());
            m.put("status", c.getStatus().name());
            m.put("createdAt", c.getCreatedAt());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> connections() {
        List<Map<String, Object>> out = new ArrayList<>();
        // No tenant-wide finder exists; gather per company (all tenant-scoped).
        for (Company c : companies.findAllByOrderByCreatedAtDesc()) {
            for (PlatformConnection conn : connections.findByCompanyIdOrderByCreatedAtDesc(c.getId())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", conn.getId());
                m.put("companyId", conn.getCompanyId());
                m.put("platform", conn.getPlatform().name());
                m.put("status", conn.getStatus().name());
                m.put("health", conn.getHealth() == null ? null : conn.getHealth().name());
                m.put("maskedKey", conn.getApiKeyLast4() == null ? null : "••••" + conn.getApiKeyLast4());
                m.put("createdAt", conn.getCreatedAt());
                out.add(m);
            }
        }
        return out;
    }
}
