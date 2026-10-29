# CMO² Master Command Platform
## Document 2 of 5 — Project Architecture

**Version:** 1.0 | **Date:** June 2026
**Companion documents:** 01 Overview · 03 High-Level Plan · 04 Technology Stack · 05 Features

---

## 1. Architectural Style: Modular Monolith

The system is built as a **single deployable Spring Boot application organized into strictly bounded internal modules**, with module boundaries clean enough that any module can be extracted into a microservice later without rewriting.

**Why not microservices from day one?** Microservices multiply deployment, networking, debugging, and security surface area. For a small team building v1, a modular monolith delivers the same clean boundaries inside one process — one deploy, one database connection pool, one log stream — while preserving the option to split later (the "monolith-first" strategy used successfully by Shopify, Basecamp, and Stack Overflow).

**Module extraction candidates** (if/when scale demands): the AI generation service (CPU/cost isolation), the integrations sync workers (independent scaling), and the analytics aggregator.

---

## 2. System Context Diagram

```
┌──────────────────────────────────────────────────────────────┐
│                        BROWSER CLIENT                        │
│      React 19 SPA (Vite) — Tailwind CSS + shadcn/ui          │
│  Auth pages · 11 module views · WebSocket live updates       │
│  Stripe Checkout / Customer Portal (hosted by Stripe)        │
└───────────────────────────┬──────────────────────────────────┘
                            │ HTTPS (TLS 1.3) — REST JSON + STOMP WebSocket
┌───────────────────────────▼──────────────────────────────────┐
│            EDGE / GATEWAY (within Spring Boot)               │
│  Spring Security 6 filter chain (executed in order):         │
│  1. TLS termination (reverse proxy: Nginx/Caddy)             │
│  2. Rate limiter (Bucket4j + Redis, per-tenant + per-IP)     │
│  3. JWT validation → principal                               │
│  4. Tenant resolution → TenantContext (ThreadLocal)          │
│  5. RBAC authorization (method-level @PreAuthorize)          │
└───────────────────────────┬──────────────────────────────────┘
┌───────────────────────────▼──────────────────────────────────┐
│        SPRING BOOT 3 MODULAR MONOLITH (Java 17)              │
│                                                              │
│  BUSINESS MODULES                                            │
│  ├── auth          login, register, MFA, refresh, reset      │
│  ├── tenancy       tenants, companies, onboarding            │
│  ├── billing       Stripe plans, subscriptions, usage        │
│  ├── integrations  15 platform connectors + webhooks         │
│  ├── assets        AI generation, templates, i18n, deploy    │
│  ├── strategists   17 agents, sessions, review gates         │
│  ├── strangeloop   orchestrator + 5 stage services           │
│  ├── analytics     KPI ingestion, aggregation, reports       │
│  └── alerts        rules engine, email/SMS/Slack channels    │
│                                                              │
│  SHARED KERNEL                                               │
│  ├── events        in-process event bus (ApplicationEvents)  │
│  ├── crypto        AES-256-GCM envelope encryption           │
│  ├── audit         immutable audit interceptor               │
│  ├── scheduling    Quartz jobs (loop cycles, syncs)          │
│  └── websocket     STOMP broker relay                        │
└──────┬───────────────┬───────────────┬───────────────────────┘
       │               │               │
┌──────▼─────┐  ┌──────▼─────┐  ┌──────▼───────────────────────┐
│  MySQL 8   │  │  Redis 7   │  │  EXTERNAL SERVICES           │
│ (primary   │  │ cache ·    │  │ Claude/OpenAI · GoHighLevel  │
│  store,    │  │ rate-limit │  │ Meta · LinkedIn · GoogleAds  │
│  Flyway-   │  │ counters · │  │ ReachInbox · ManyChat · ...  │
│  migrated) │  │ job queue  │  │ Stripe · Twilio · SendGrid   │
└────────────┘  └────────────┘  │ Slack · Notion · Linear      │
                                └──────────────────────────────┘
```

---

## 3. Multi-Tenancy Architecture (The Critical Design)

**Model:** Shared database, shared schema, **tenant-discriminator column** (`tenant_id`) on every tenant-owned table.

| Alternative considered | Verdict |
|---|---|
| Database-per-tenant | Rejected for v1 — operational overhead (migrations × N, backups × N) unjustified at this stage |
| Schema-per-tenant | Rejected — MySQL schema sprawl, complex connection routing |
| **Shared schema + discriminator** | ✅ Chosen — simplest to operate, scales to thousands of tenants, isolation enforced in code at the ORM layer |

**Enforcement chain (defense in depth):**

1. **JWT claim** — every access token carries `tenant_id`, signed and tamper-proof.
2. **TenantFilter (servlet filter)** — extracts the claim into a `TenantContext` ThreadLocal at request entry; cleared at request exit.
3. **Hibernate `@TenantId` / session filter** — automatically appends `WHERE tenant_id = :current` to **every** query on tenant-owned entities. Developers physically cannot forget the clause.
4. **Entity listener** — auto-stamps `tenant_id` on every INSERT from context, never from client input.
5. **Tenant-leak test suite** — CI integration tests that authenticate as Tenant A and attempt to read/write Tenant B's data on every endpoint; any leak fails the build.

**Tenant data hierarchy:**

```
Tenant (paying customer — agency or business)
├── Users (team members with roles)
├── Subscription (Stripe-linked plan + limits)
└── Companies (brands managed — the Multi-Company module)
    ├── Platform Connections (encrypted credentials)
    ├── Assets (generated content, versioned, EN/ES)
    ├── Strategist Sessions (AI agent conversations + gates)
    ├── Loop Iterations (Strange Loop history)
    └── KPI Snapshots / Alerts / Reports
```

---

## 4. Security Architecture

### 4.1 Authentication
- Spring Security 6 with **stateless JWT**: access token ~15 min TTL; rotating refresh token ~7 days in an `httpOnly; Secure; SameSite=Strict` cookie.
- **BCrypt** (cost 12) password hashing. Email verification on signup. Single-use, expiring password-reset tokens. Lockout after 5 failed attempts.
- **TOTP MFA** (authenticator apps) — mandatory for OWNER/ADMIN, optional otherwise.
- **OAuth2 social login** (Google, Microsoft) via Spring OAuth2 Client.

### 4.2 Authorization (RBAC)
Five roles enforced at the **service layer** (UI checks are convenience only):

| Role | Scope |
|---|---|
| OWNER | All + billing + tenant deletion |
| ADMIN | Users, companies, integrations |
| STRATEGIST | AI agents, asset generation, deployment |
| ANALYST | Analytics and reports (read) |
| VIEWER | Read-only dashboards |

### 4.3 Secrets & Credential Storage
- Customer platform API keys: **AES-256-GCM envelope encryption** — per-tenant data keys wrapped by a master key held in environment/KMS. Compromise of one tenant's key exposes nothing else.
- Keys are write-only from the client's perspective: after save, the UI receives only `••••last4`.
- Application secrets via environment variables / Docker secrets / cloud secrets manager — never in code or repo.

### 4.4 API & Transport Hardening
- TLS 1.3, HSTS, strict CORS allowlist.
- Rate limiting (Bucket4j on Redis) per tenant and per IP — doubles as AI cost protection.
- Security headers: CSP, X-Frame-Options DENY, X-Content-Type-Options, Referrer-Policy.
- Bean Validation on all request DTOs; JPA parameterized queries (SQLi-resistant by construction); React's default output encoding (XSS-resistant).
- **HMAC signature verification on all inbound webhooks** (Stripe signing secret, platform HMACs).

### 4.5 Audit & Compliance Readiness
- Immutable `audit_log`: actor, action, tenant, IP, before/after diff, timestamp.
- GDPR groundwork: per-tenant data export endpoint, soft-delete + scheduled purge, consent flags.
- Structured JSON logs with correlation IDs; PII excluded from logs.
- CI security gates: OWASP Dependency-Check, Dependabot, secret scanning.

---

## 5. Backend Module Structure (Package-by-Feature)

```
cmo2-backend/src/main/java/com/cmo2/
├── Cmo2Application.java
├── shared/
│   ├── config/          SecurityConfig, CorsConfig, OpenApiConfig, WebSocketConfig
│   ├── security/        JwtService, TenantContext, TenantFilter, RoleChecks
│   ├── crypto/          AesGcmService, EnvelopeEncryption
│   ├── events/          DomainEventPublisher (Spring ApplicationEvents)
│   ├── audit/           AuditInterceptor, AuditLogRepository
│   └── exceptions/      GlobalExceptionHandler (@ControllerAdvice)
├── auth/                controller / service / repository / dto
├── tenancy/             tenants, companies, onboarding wizard state machine
├── billing/             StripeService, WebhookController, PlanLimitGuard
├── integrations/
│   ├── core/            PlatformConnector (interface), HealthChecker, SyncScheduler
│   ├── gohighlevel/     reference connector implementation
│   ├── reachinbox/ meta/ linkedin/ googleads/ manychat/ ...
│   └── webhooks/        signed inbound webhook receivers
├── assets/              GenerationService, TemplateRepo, Translator, Deployer, Versioning
├── strategists/         AgentRegistry (17), SessionService, ReviewGateService
├── strangeloop/
│   ├── orchestrator/    LoopOrchestrator, IterationRepository
│   └── stages/          Stage1Innovation … Stage5Optimization
├── analytics/           KpiIngestor, Aggregator, ReportGenerator (PDF)
├── alerts/              RuleEngine, channels: EmailChannel, SmsChannel, SlackChannel
└── ai/                  AiService (provider-agnostic), PromptRegistry, TokenAccountant, CostGuard
```

**The Connector Pattern** — one interface, fifteen implementations:

```java
public interface PlatformConnector {
    Platform platform();
    ConnectionHealth testConnection(EncryptedCredentials creds);
    SyncResult pullData(SyncRequest request);   // contacts, campaigns, KPIs
    DeployResult deployAsset(Asset asset);      // push generated content
}
```

GoHighLevel is built first as the reference implementation; the remaining 14 platforms replicate the pattern (~2–3 days each instead of weeks).

---

## 6. Frontend Architecture (React 19)

The existing HTML prototype serves as the **design specification** — glassmorphism theme, collapsible sidebar, module badges, and layout are recreated as components.

```
cmo2-frontend/src/
├── app/
│   ├── AppShell.tsx        sidebar + topbar + breadcrumb + company switcher
│   ├── routes.tsx          /dashboard /control /intelligence /companies /assets
│   │                       /strategists /strange-loop /analytics /reports
│   │                       /integrations /settings /billing
│   └── guards/             RequireAuth, RequireRole
├── features/               one folder per module, mirroring backend modules
│   ├── auth/  dashboard/  companies/  assets/  strategists/
│   ├── strangeLoop/  integrations/  analytics/  reports/
│   ├── alerts/  settings/  billing/
├── components/ui/          shadcn primitives + GlassCard, StatBadge, TrafficLight
├── lib/
│   ├── api.ts              Axios instance: auth header, 401→silent refresh, retry
│   ├── ws.ts               STOMP client: loop progress, activity feed, alerts
│   └── queryClient.ts      TanStack Query defaults (staleTime, retries)
└── stores/                 Zustand: session, selectedCompany, ui prefs
```

**State strategy:** server state lives in **TanStack Query** (caching, refetching, optimistic updates); minimal client state in **Zustand**; forms via **React Hook Form + Zod** schemas shared conceptually with backend validation.

**Real-time strategy:** one authenticated WebSocket (STOMP over SockJS) carrying topic streams — `/topic/tenant.{id}.loop`, `.activity`, `.alerts` — so loop stage progress bars, the activity feed, and alert toasts update without polling.

---

## 7. Strange Loop Engine Architecture

```java
@Service
public class LoopOrchestrator {
    @Transactional
    public LoopIteration runCycle(Long companyId) {
        var iteration = startIteration(companyId);
        var strategy  = stage1Innovation.run(iteration);   // AI strategy generation
        var insights  = stage2Learning.run(strategy);      // pattern analysis on KPI data
        stage3Documentation.run(insights);                 // push to Notion/Confluence/Linear
        var gaps      = stage4Reporting.run(insights);     // gap identification vs targets
        stage5Optimization.run(gaps);                      // apply fixes, fire alerts
        scheduleNext(iteration);                            // loop back (Quartz)
        return complete(iteration);
    }
}
```

- Each stage emits domain events → WebSocket broadcast → live progress bars in the Strange Loop module.
- Stage transitions are persisted (`loop_stage_runs`) so crashed cycles resume, not restart.
- Stage 2 consumes `kpi_snapshots`; Stage 5 outputs feed the alert engine and the optimization-suggestion queue.
- Roadmap phases 10–13 (predictive alerts, optimization suggestions, What-If simulator, AI Co-Pilot) plug in as AI analyzers inside Stages 2, 4, and 5 — no architectural change required.

---

## 8. Data Architecture (MySQL 8)

All schema changes via **Flyway** versioned migrations (`V1__init.sql`, `V2__assets.sql`, …). Every tenant-owned table carries `tenant_id` with composite indexes `(tenant_id, …)`.

Core table groups (full DDL in the build plan):

| Group | Tables |
|---|---|
| Identity & tenancy | tenants, users, roles, user_roles, companies |
| Billing | plans, subscriptions, usage_records |
| Integrations | platform_connections, sync_jobs, webhook_events |
| Assets | asset_templates, assets (versioned), asset_deployments |
| Strategists | strategist_agents, strategist_sessions, strategist_messages, review_gates |
| Strange Loop | loop_iterations, loop_stage_runs, loop_triggers |
| Analytics & alerts | kpi_snapshots, alert_rules, alerts, activity_log, audit_log |

JSON columns (`content_json`, `insights_json`, `limits_json`) hold flexible AI payloads while relational columns hold everything queried/filtered — the pragmatic MySQL 8 hybrid.

---

## 9. Integration & Eventing Patterns

- **Outbound (pull):** Quartz-scheduled `SyncScheduler` invokes each healthy connector's `pullData`; results land in `kpi_snapshots` and platform entities; failures update traffic-light `health` and raise alerts.
- **Inbound (push):** webhook receivers verify HMAC signatures, persist raw payloads to `webhook_events` (replayable), then process asynchronously.
- **Internal:** Spring ApplicationEvents decouple modules — e.g., `AssetGeneratedEvent` triggers translation, documentation, deployment, and Stage-2 learning without the generator knowing any of them exist.
- **AI:** a single provider-agnostic `AiService` routes tasks to model tiers (cheap model for translation, frontier model for strategy), meters tokens per tenant, and enforces `CostGuard` ceilings with circuit breakers.

---

## 10. Deployment Architecture

```
┌──────────── Developer laptop ────────────┐
│ docker compose up:                       │
│ backend · frontend · mysql · redis ·     │
│ mailhog (catches outbound email locally) │
└──────────────────────────────────────────┘
                 │ git push
                 ▼
┌──────────── GitHub Actions CI ───────────┐
│ build → unit tests → Testcontainers      │
│ integration tests (real MySQL) →         │
│ tenant-leak suite → OWASP scan →         │
│ Docker image → push registry             │
└──────────────────────────────────────────┘
                 │
        staging (auto) → production (manual gate)
                 ▼
┌──────────── Production (v1) ─────────────┐
│ VPS / AWS Lightsail → later ECS + RDS    │
│ Nginx (TLS) → Spring Boot container      │
│ MySQL (managed or container + backups)   │
│ Redis · Prometheus/Grafana · Sentry      │
│ Nightly dumps + binlog; tested restores  │
└──────────────────────────────────────────┘
```

Observability: Spring Boot Actuator + Micrometer metrics, Grafana dashboards, Sentry error tracking, uptime probe on `/actuator/health`.
