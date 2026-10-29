package com.cmo2.shared.demo;

import com.cmo2.alerts.dto.AlertDtos.ChannelSpec;
import com.cmo2.alerts.dto.AlertDtos.CreateRuleRequest;
import com.cmo2.alerts.service.AlertService;
import com.cmo2.assets.service.GenerationService;
import com.cmo2.auth.domain.Role;
import com.cmo2.auth.domain.RoleName;
import com.cmo2.auth.domain.User;
import com.cmo2.auth.domain.UserStatus;
import com.cmo2.auth.repository.RoleRepository;
import com.cmo2.auth.repository.UserRepository;
import com.cmo2.billing.service.BillingLifecycleService;
import com.cmo2.integrations.dto.IntegrationDtos.ConnectRequest;
import com.cmo2.integrations.service.ConnectionService;
import com.cmo2.integrations.service.SyncService;
import com.cmo2.reports.service.ReportService;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.strangeloop.service.LoopService;
import com.cmo2.strategists.service.ReviewGateService;
import com.cmo2.strategists.service.SessionService;
import com.cmo2.tenancy.domain.PlanCode;
import com.cmo2.tenancy.domain.Tenant;
import com.cmo2.tenancy.domain.TenantStatus;
import com.cmo2.tenancy.dto.CompanyDtos.CreateCompanyRequest;
import com.cmo2.tenancy.repository.TenantRepository;
import com.cmo2.tenancy.service.CompanyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Seeds a polished, fully-populated demo tenant — only when the {@code demo} Spring profile is
 * active ({@code SPRING_PROFILES_ACTIVE=demo}). Idempotent: it no-ops if the demo owner already
 * exists, so restarts are safe.
 *
 * <p>It drives the <b>real services</b> with a tenant + OWNER security context bound, so the
 * seeded data is exactly what a real user would create (no special back-doors). With the default
 * stub/demo providers this needs no API keys: realistic KPIs, AI content, alerts, and reports all
 * generate locally.
 *
 * <p>Login for every seeded user is password <b>{@code demo1234}</b> (see the banner logged at
 * the end). Never enable the {@code demo} profile in production.
 */
@Component
@Profile("demo")
@Order(100) // after RoleSeeder(10) + AgentSeeder(50): roles/agents must exist first
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final String OWNER_EMAIL = "owner@demo.cmo2";
    private static final String DEMO_PASSWORD = "demo1234";
    private static final int SYNCS_PER_CONNECTION = 6;

    private final TenantRepository tenants;
    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder encoder;
    private final BillingLifecycleService billingLifecycle;
    private final CompanyService companyService;
    private final ConnectionService connectionService;
    private final SyncService syncService;
    private final AlertService alertService;
    private final GenerationService generationService;
    private final SessionService sessionService;
    private final ReviewGateService reviewGateService;
    private final LoopService loopService;
    private final ReportService reportService;

    public DemoDataSeeder(TenantRepository tenants, UserRepository users, RoleRepository roles,
                          PasswordEncoder encoder, BillingLifecycleService billingLifecycle,
                          CompanyService companyService, ConnectionService connectionService,
                          SyncService syncService, AlertService alertService,
                          GenerationService generationService, SessionService sessionService,
                          ReviewGateService reviewGateService, LoopService loopService,
                          ReportService reportService) {
        this.tenants = tenants;
        this.users = users;
        this.roles = roles;
        this.encoder = encoder;
        this.billingLifecycle = billingLifecycle;
        this.companyService = companyService;
        this.connectionService = connectionService;
        this.syncService = syncService;
        this.alertService = alertService;
        this.generationService = generationService;
        this.sessionService = sessionService;
        this.reviewGateService = reviewGateService;
        this.loopService = loopService;
        this.reportService = reportService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.existsByEmail(OWNER_EMAIL)) {
            log.info("Demo data already present — skipping demo seed.");
            return;
        }
        log.info("Seeding demo tenant (profile=demo)…");

        Tenant tenant = new Tenant();
        tenant.setName("Demo Agency");
        tenant.setStatus(TenantStatus.TRIAL);
        tenant.setPlanCode(PlanCode.STARTER);
        tenant.setTrialEndsAt(Instant.now().plus(14, ChronoUnit.DAYS));
        tenant = tenants.save(tenant);
        Long tenantId = tenant.getId();

        try {
            // Bind tenant + an OWNER security context so the real (secured) services accept calls.
            TenantContext.set(tenantId, null);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("demo-seeder", null,
                            List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));

            User owner = createUser(OWNER_EMAIL, "Olivia Owner", RoleName.OWNER);
            TenantContext.set(tenantId, owner.getId());
            createUser("admin@demo.cmo2", "Adam Admin", RoleName.ADMIN);
            createUser("strategist@demo.cmo2", "Sasha Strategist", RoleName.STRATEGIST);
            createUser("analyst@demo.cmo2", "Ana Analyst", RoleName.ANALYST);
            createUser("viewer@demo.cmo2", "Vic Viewer", RoleName.VIEWER);

            // Put the tenant on an active PRO plan (lifts Starter limits for a richer demo).
            billingLifecycle.activate(tenantId, PlanCode.PRO, "cus_demo", "sub_demo",
                    Instant.now().plus(30, ChronoUnit.DAYS));

            long northwind = company("Northwind Solar", "Renewable energy",
                    "Confident, sustainability-led", "https://northwind.example");
            long brightDental = company("Bright Dental", "Healthcare",
                    "Warm, reassuring, professional", "https://brightdental.example");
            long apexFitness = company("Apex Fitness", "Fitness",
                    "High-energy, motivational", "https://apexfitness.example");

            // Connect platforms and build KPI history so charts + forecasts look real.
            connectAndSync(northwind, "GOHIGHLEVEL", "demo-key-123456");
            connectAndSync(brightDental, "GOHIGHLEVEL", "demo-key-345678");
            connectAndSync(apexFitness, "GOHIGHLEVEL", "demo-key-789012");

            enrich(northwind, brightDental, apexFitness);

            logBanner();
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /** Best-effort extras — a failure in any one shouldn't abort the whole seed. */
    private void enrich(long northwind, long brightDental, long apexFitness) {
        safe("alert rules + fired alert", () -> {
            alertService.createRule(northwind, new CreateRuleRequest("cpl", "GT", 40.0, "WARNING",
                    List.of(new ChannelSpec("EMAIL", "ops@demo.cmo2"), new ChannelSpec("SLACK", null))));
            alertService.createRule(brightDental, new CreateRuleRequest("conversion_rate", "LT", 2.0, "CRITICAL",
                    List.of(new ChannelSpec("EMAIL", "ops@demo.cmo2"))));
            // Trip the CPL rule so the alert center isn't empty.
            alertService.simulate(northwind, "cpl", 95.0);
        });

        safe("generated asset", () ->
                generationService.generate(northwind, "EMAIL_SEQUENCE",
                        "A 7-email welcome sequence for new solar consultation leads."));

        safe("strategist session + RG-1", () -> {
            var session = sessionService.start(northwind, "MARKET_ANALYSIS", null);
            sessionService.produceDeliverables(session.id());
            reviewGateService.approve(northwind, "RG-1", "Approved for the demo walkthrough.");
        });

        safe("Strange Loop cycle", () -> loopService.runCycle(northwind));

        safe("leadership reports", () -> {
            reportService.generateForCompany(northwind);
            reportService.generateForCompany(apexFitness);
        });
    }

    private long company(String name, String industry, String voice, String website) {
        return companyService.create(new CreateCompanyRequest(name, industry, voice, website)).id();
    }

    private void connectAndSync(long companyId, String platform, String demoKey) {
        try {
            var conn = connectionService.connect(companyId, new ConnectRequest(platform, demoKey, null));
            for (int i = 0; i < SYNCS_PER_CONNECTION; i++) {
                syncService.triggerManualSync(conn.id());
            }
        } catch (Exception e) {
            log.warn("Demo seed: connect/sync {} for company {} failed: {}", platform, companyId, e.getMessage());
        }
    }

    private User createUser(String email, String fullName, RoleName roleName) {
        Role role = roles.findByName(roleName)
                .orElseThrow(() -> new IllegalStateException(roleName + " role missing — check RoleSeeder"));
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(DEMO_PASSWORD));
        u.setFullName(fullName);
        u.setStatus(UserStatus.ACTIVE);
        u.setEmailVerified(true);
        u.addRole(role);
        return users.save(u);
    }

    private void safe(String what, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("Demo seed: {} failed: {}", what, e.getMessage());
        }
    }

    private void logBanner() {
        log.info("""

                ============================================================
                  CMO² DEMO DATA READY  ·  tenant "Demo Agency" (PRO, active)
                  Sign in at http://localhost:5173  ·  password: {}
                    owner@demo.cmo2        (OWNER)
                    admin@demo.cmo2        (ADMIN)
                    strategist@demo.cmo2   (STRATEGIST)
                    analyst@demo.cmo2      (ANALYST)
                    viewer@demo.cmo2       (VIEWER)
                  3 customers · GoHighLevel demo figures · drafts in Approvals · a cycle · reports
                ============================================================
                """, DEMO_PASSWORD);
    }
}
