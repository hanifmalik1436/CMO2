# CMO² SaaS Platform — Complete Architecture & Build Plan
## Java 17 + Spring Boot 3.x | React 19 | MySQL 8 | Multi-Tenant SaaS

**Version:** 1.0 | **Date:** June 2026
**Source materials:** CMO2 Dashboard Audit, Optimal Build Order, Strange Loop OS docs, Integrated HTML frontend

---

## 0. QUICK CORRECTION ON VERSIONS (Important!)

You asked for "React 20" — that version doesn't exist yet. The current stable version is **React 19**. We'll use:

| Layer | Technology | Version | Why |
|---|---|---|---|
| Backend language | Java | 17 (LTS) | Long-term support, records, sealed classes, pattern matching |
| Backend framework | Spring Boot | 3.3.x | Requires Java 17+, native Spring Security 6, observability built in |
| Frontend | React | 19 | Latest stable; Server Components ready, improved hooks |
| Frontend build | Vite | 5.x | Fast dev server, replaces Create React App (deprecated) |
| UI framework | Tailwind CSS + shadcn/ui | latest | Matches your glassmorphism design system |
| Database | MySQL | 8.0+ | Your choice; JSON column support, window functions |
| ORM | Spring Data JPA (Hibernate) | 6.x | Standard for Spring Boot |
| Migrations | Flyway | latest | Version-controlled database changes |
| Cache / queues | Redis | 7.x | Sessions, rate limiting, job queues |
| Background jobs | Spring Scheduler + Quartz | — | Strange Loop automation cycles |
| Real-time | WebSocket (STOMP) | — | Live dashboard updates, activity feed |
| AI | Anthropic Claude API / OpenAI | — | Asset generation, AI Strategists, predictive alerts |
| Payments | Stripe | — | SaaS subscription billing |
| Containerization | Docker + Docker Compose | — | Identical dev/prod environments |

---

## 1. WHAT WE'RE BUILDING (Current Reality → Target)

**Current state (from your audit):** A beautiful 12,336-line HTML/CSS/JS shell that's ~20% complete. The navigation, theming, and module structure work. Almost every button shows a placeholder alert. There is no backend, no database, no auth, no real integrations.

**Target state:** A fully functional, multi-tenant SaaS product with:
- 11 working modules (Dashboard, Master Control, Intelligence Hub, Multi-Company, Asset Generator, AI Strategist, Analytics, Reports, Settings, Integrations, Strange Loop OS)
- 15 platform integrations (GoHighLevel, ReachInbox, LinkedIn, Meta, Google Ads, Stripe, etc.)
- 17 AI Strategist agents across 4 phases
- The 5-stage Strange Loop automation engine (Innovation → Learning → Documentation → Reporting → Optimization)
- Subscription billing, team management, and enterprise-grade security

---

## 2. HIGH-LEVEL ARCHITECTURE

We'll build a **Modular Monolith** first (one deployable Spring Boot app organized into clean modules), designed so any module can later be split into a microservice without rewriting.

> **Beginner concept — Why not microservices from day one?** Microservices mean running 10+ separate apps that talk over a network. That multiplies deployment, debugging, and security work. A modular monolith gives you the same clean boundaries inside ONE app. Companies like Shopify and Basecamp run massive products this way.

```
┌─────────────────────────────────────────────────────────────┐
│                     CLIENT (Browser)                        │
│   React 19 SPA (Vite) — Tailwind + shadcn/ui                │
│   ├── Auth pages (login, register, MFA, forgot password)    │
│   ├── 11 Module views (mirroring your current HTML UI)     │
│   ├── WebSocket client (live activity feed, loop status)   │
│   └── Stripe Checkout / Customer Portal                     │
└──────────────────────────┬──────────────────────────────────┘
                           │ HTTPS (TLS 1.3) — JSON REST + WebSocket
┌──────────────────────────▼──────────────────────────────────┐
│              API GATEWAY LAYER (inside Spring Boot)          │
│   Spring Security 6 filter chain:                            │
│   JWT validation → Tenant resolution → RBAC → Rate limiting  │
└──────────────────────────┬──────────────────────────────────┘
┌──────────────────────────▼──────────────────────────────────┐
│            SPRING BOOT 3 MODULAR MONOLITH (Java 17)          │
│                                                              │
│  ┌─────────────┐ ┌──────────────┐ ┌───────────────────────┐ │
│  │ auth         │ │ tenancy      │ │ billing (Stripe)      │ │
│  │ users/roles  │ │ companies    │ │ plans/subscriptions   │ │
│  └─────────────┘ └──────────────┘ └───────────────────────┘ │
│  ┌─────────────┐ ┌──────────────┐ ┌───────────────────────┐ │
│  │ integrations │ │ assets       │ │ ai-strategists        │ │
│  │ 15 platforms │ │ generator    │ │ 17 agents, 4 phases   │ │
│  └─────────────┘ └──────────────┘ └───────────────────────┘ │
│  ┌─────────────┐ ┌──────────────┐ ┌───────────────────────┐ │
│  │ strange-loop │ │ analytics    │ │ alerts/notifications  │ │
│  │ 5-stage eng. │ │ KPIs/reports │ │ email/SMS/Slack       │ │
│  └─────────────┘ └──────────────┘ └───────────────────────┘ │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ shared kernel: events bus, audit log, encryption,    │   │
│  │ scheduling, webhooks, file storage                   │   │
│  └──────────────────────────────────────────────────────┘   │
└──────┬──────────────┬──────────────┬───────────────────────┘
       │              │              │
┌──────▼─────┐ ┌──────▼─────┐ ┌──────▼──────────────────────┐
│ MySQL 8    │ │ Redis 7    │ │ External APIs               │
│ (Flyway    │ │ cache,     │ │ Claude/OpenAI, GoHighLevel, │
│ migrations)│ │ rate-limit,│ │ Meta, LinkedIn, Google Ads, │
│            │ │ job queue  │ │ Stripe, Twilio, Slack...    │
└────────────┘ └────────────┘ └─────────────────────────────┘
```

---

## 3. MULTI-TENANCY DESIGN (The Heart of "Sellable SaaS")

> **Beginner concept — Multi-tenancy** means many customers (tenants) share one application and one database, but can NEVER see each other's data. Getting this wrong is the #1 way SaaS companies get destroyed (data leaks between customers).

**Strategy: Shared database, shared schema, with a `tenant_id` discriminator column** — the right choice for MySQL and for your stage.

How it works in Spring Boot:
1. Every JWT contains a `tenant_id` claim.
2. A servlet filter extracts it into a `TenantContext` (a ThreadLocal holder).
3. A Hibernate filter (`@Filter` / `@TenantId` in Hibernate 6) automatically appends `WHERE tenant_id = ?` to **every** query. Developers cannot forget it.
4. An `@EntityListener` automatically stamps `tenant_id` on every insert.
5. Integration tests assert cross-tenant isolation on every endpoint (a "tenant leak test suite").

**Tenant hierarchy** (matches your Multi-Company module):
```
Tenant (the paying customer / agency)
 └── Companies (the brands they manage — your Multi-Company Manager)
      └── Campaigns, Assets, Integrations, Strategist sessions, Loop iterations
 └── Users (team members with roles)
```

---

## 4. SECURITY ARCHITECTURE (Full Stack, SaaS-Grade)

### 4.1 Authentication
- **Spring Security 6 + JWT** (short-lived access token ~15 min, rotating refresh token ~7 days stored as httpOnly Secure SameSite=Strict cookie)
- **BCrypt** password hashing (strength 12)
- **MFA/2FA** via TOTP (Google Authenticator) — required for Admin role, optional for others
- **OAuth2 social login** (Google, Microsoft) via Spring Security OAuth2 Client
- Account lockout after 5 failed attempts; email verification on signup; secure password reset with single-use expiring tokens

### 4.2 Authorization (RBAC)
| Role | Capabilities |
|---|---|
| OWNER | Everything + billing + delete tenant |
| ADMIN | Manage users, companies, integrations |
| STRATEGIST | Run AI agents, generate/deploy assets |
| ANALYST | View analytics/reports only |
| VIEWER | Read-only dashboards |

Implemented with `@PreAuthorize("hasRole('ADMIN')")` method security + permission checks at the service layer (never trust the UI alone).

### 4.3 Secrets & API Key Storage (Critical for your Integrations module)
- Customer platform API keys encrypted at rest with **AES-256-GCM**, key supplied via environment/KMS (never in code or DB)
- Envelope encryption: per-tenant data keys wrapped by a master key → one tenant's compromise doesn't expose others
- Keys never returned to the frontend after save (show `••••last4` only)
- App secrets in environment variables / Docker secrets / AWS Secrets Manager

### 4.4 API & Transport Security
- TLS 1.3 everywhere; HSTS header
- **Rate limiting** per tenant + per IP (Bucket4j + Redis) — also protects your AI cost budget
- Strict CORS allowlist; CSRF protection on cookie-based flows
- Security headers: CSP, X-Content-Type-Options, X-Frame-Options, Referrer-Policy
- Bean Validation (`@Valid`) on every request DTO; JPA parameterized queries (SQL injection-proof by default); output encoding in React (XSS-resistant by default)
- Webhook signature verification (Stripe signing secret, GHL HMAC) on all inbound webhooks

### 4.5 Auditing & Compliance Readiness
- Immutable `audit_log` table: who, what, when, tenant, IP, before/after diff
- GDPR groundwork: data export endpoint, soft-delete + purge job, consent flags
- Structured JSON logging (no PII in logs), correlation IDs per request
- Dependency scanning (OWASP Dependency-Check, Dependabot) in CI

---

## 5. DATABASE DESIGN (MySQL 8 — Core Schema)

> **Beginner concept — Migrations.** Never change the database by hand. Flyway runs numbered SQL files (V1__init.sql, V2__add_assets.sql...) so every environment is identical and changes are reversible/reviewable.

```sql
-- TENANCY & IDENTITY
tenants(id, name, plan_id, status, stripe_customer_id, created_at)
users(id, tenant_id, email, password_hash, mfa_secret_enc, status, last_login_at)
roles(id, name) / user_roles(user_id, role_id)
companies(id, tenant_id, name, industry, brand_settings_json, onboarding_step)

-- BILLING (SaaS)
plans(id, name, price_cents, interval, limits_json)          -- Starter/Pro/Enterprise
subscriptions(id, tenant_id, plan_id, stripe_sub_id, status, period_end)
usage_records(id, tenant_id, metric, quantity, period)        -- AI tokens, assets, seats

-- INTEGRATIONS (15 platforms)
platform_connections(id, tenant_id, company_id, platform, api_key_enc,
                     status, last_sync_at, health)            -- health: GREEN/YELLOW/RED
sync_jobs(id, connection_id, type, status, started_at, finished_at, error_json)
webhook_events(id, tenant_id, source, payload_json, signature_valid, processed_at)

-- ASSET GENERATOR (7 asset types)
asset_templates(id, type, language, body_template, variables_json)
assets(id, tenant_id, company_id, type, language, status, content_json,
       version, parent_asset_id)                              -- versioning built in
asset_deployments(id, asset_id, platform, status, deployed_at, external_ref)

-- AI STRATEGIST (17 agents, 4 phases)
strategist_agents(id, code, name, phase, deliverables_json, system_prompt)
strategist_sessions(id, tenant_id, company_id, agent_id, status, context_json)
strategist_messages(id, session_id, role, content, tokens_used, created_at)
review_gates(id, session_id, gate_code, approved_by, approved_at)  -- RG-1..RG-4

-- STRANGE LOOP ENGINE (5 stages)
loop_iterations(id, tenant_id, company_id, iteration_no, status,
                started_at, completed_at, insights_json, gaps_json)
loop_stage_runs(id, iteration_id, stage_no, stage_name, status,
                progress_pct, data_json, started_at, completed_at)
loop_triggers(id, tenant_id, trigger_type, stage_no, conditions_json,
              actions_json, is_active)

-- ANALYTICS & ALERTS
kpi_snapshots(id, tenant_id, company_id, platform, metric, value,
              captured_at)                                    -- Leads, CPL, ROAS, CTR...
alert_rules(id, tenant_id, metric, condition, threshold, channels_json)
alerts(id, rule_id, severity, message, status, fired_at, acked_by)
activity_log(id, tenant_id, actor_id, action, entity, entity_id, meta_json)
audit_log(id, tenant_id, actor_id, action, before_json, after_json, ip, at)
```

Every tenant-owned table carries `tenant_id` + composite index `(tenant_id, ...)` for fast isolated queries.

---

## 6. BACKEND PROJECT STRUCTURE (Spring Boot, Package-by-Module)

```
cmo2-backend/
├── src/main/java/com/cmo2/
│   ├── Cmo2Application.java
│   ├── shared/                    # Shared kernel
│   │   ├── config/                # Security, CORS, Jackson, OpenAPI
│   │   ├── security/              # JWT filters, TenantContext, RBAC
│   │   ├── crypto/                # AES-GCM service, envelope encryption
│   │   ├── events/                # Spring ApplicationEvent bus
│   │   ├── audit/                 # Audit interceptor
│   │   └── exceptions/            # Global @ControllerAdvice handler
│   ├── auth/                      # register, login, MFA, refresh, password reset
│   ├── tenancy/                   # tenants, companies, onboarding wizard
│   ├── billing/                   # Stripe: checkout, webhooks, plan limits
│   ├── integrations/
│   │   ├── core/                  # PlatformConnector interface, health checks
│   │   ├── gohighlevel/           # First connector (reference implementation)
│   │   ├── reachinbox/ meta/ linkedin/ googleads/ ...
│   │   └── webhooks/              # Inbound webhook receivers + verification
│   ├── assets/                    # generation, templates, translation, versioning, deployment
│   ├── strategists/               # 17 agents, sessions, review gates, prompt mgmt
│   ├── strangeloop/
│   │   ├── orchestrator/          # LoopOrchestrator (the cycle runner)
│   │   └── stages/                # Stage1Innovation ... Stage5Optimization
│   ├── analytics/                 # KPI ingestion, aggregation, reports
│   ├── alerts/                    # rules engine, channels (email/SMS/Slack)
│   └── ai/                        # Claude/OpenAI client, token accounting, cost guard
├── src/main/resources/
│   ├── application.yml            # + application-prod.yml profiles
│   └── db/migration/              # Flyway: V1__init.sql, V2__...
└── src/test/java/                 # Unit + integration + tenant-leak tests
```

**Connector pattern (one interface, 15 implementations):**
```java
public interface PlatformConnector {
    Platform platform();
    ConnectionHealth testConnection(EncryptedCredentials creds);
    SyncResult pullData(SyncRequest request);     // contacts, campaigns, KPIs
    DeployResult deployAsset(Asset asset);        // push generated content
}
```
Build GoHighLevel first as the reference; the remaining 14 platforms copy the pattern.

---

## 7. FRONTEND ARCHITECTURE (React 19 + Vite)

Your existing HTML dashboard becomes the **design spec** — we rebuild it as components, keeping the glassmorphism look, sidebar, badges, and module layout.

```
cmo2-frontend/
├── src/
│   ├── app/                       # Router, providers, layout shell
│   │   ├── AppShell.tsx           # Sidebar + topbar + breadcrumb (your current chrome)
│   │   └── routes.tsx             # /dashboard /control /intelligence /companies
│   │                              # /assets /strategists /analytics /reports
│   │                              # /settings /integrations /strange-loop
│   ├── features/                  # One folder per module (mirrors backend)
│   │   ├── auth/                  # Login, Register, MFA, ForgotPassword
│   │   ├── dashboard/             # Platform cards, charts, activity feed
│   │   ├── companies/             # Multi-company manager + onboarding wizard
│   │   ├── assets/                # 7 asset types, EN/ES toggle, template picker
│   │   ├── strategists/           # 17-agent selector, chat UI, review gates
│   │   ├── strangeLoop/           # 5-stage loop visualization (live via WebSocket)
│   │   ├── integrations/          # Connect platform, health traffic lights
│   │   ├── analytics/ reports/ alerts/ settings/ billing/
│   ├── components/ui/             # shadcn/ui primitives, GlassCard, StatBadge
│   ├── lib/
│   │   ├── api.ts                 # Axios instance w/ auth interceptor + refresh
│   │   ├── ws.ts                  # STOMP WebSocket client
│   │   └── queryClient.ts         # TanStack Query config
│   └── stores/                    # Zustand: auth, tenant, ui state
```

**Key frontend choices (and why, in plain English):**
- **TanStack Query** — handles fetching, caching, and refetching server data so you don't write loading/error boilerplate everywhere.
- **Zustand** — tiny global state store for things like "which company is selected" (simpler than Redux for beginners).
- **React Hook Form + Zod** — forms with validation that matches backend rules.
- **Recharts** — replaces Chart.js with React-native charting for KPI panels.
- **WebSocket (STOMP)** — the Strange Loop stage progress bars and activity feed update live without refreshing.
- **Route guards** — components check the user's role; but remember, the backend re-checks everything (UI security is convenience, not protection).

---

## 8. THE STRANGE LOOP ENGINE (Your Differentiator)

The 5-stage self-improving cycle, implemented as a Spring service with Quartz scheduling:

```java
@Service
public class LoopOrchestrator {
    @Transactional
    public LoopIteration runCycle(Long companyId) {
        var iteration = startIteration(companyId);
        var strategy  = stage1Innovation.run(iteration);   // AI generates strategy
        var insights  = stage2Learning.run(strategy);      // analyze KPI snapshots
        stage3Documentation.run(insights);                 // push to Notion/Confluence/Linear
        var gaps      = stage4Reporting.run(insights);     // identify gaps vs targets
        stage5Optimization.run(gaps);                      // adjust + fire alerts
        scheduleNext(iteration);                            // loop back
        return complete(iteration);
    }
}
```

- Each stage publishes events on the internal event bus → WebSocket broadcasts progress to the dashboard in real time.
- Stage 3 uses your already-connected **Notion / Linear** MCP-style API integrations for auto-documentation.
- Stages 10–13 from your PDF roadmap (Predictive Alerts, Optimization Suggestions, What-If Simulator, AI Co-Pilot) plug into Stages 2, 4 and 5 as AI-powered analyzers.

---

## 9. AI LAYER

- **One `AiService` abstraction** wrapping Claude/OpenAI so you can swap models per task (cheap model for translation, strong model for strategy).
- **Prompt registry in DB** (`strategist_agents.system_prompt`) — edit agent behavior without redeploying.
- **Token accounting per tenant** → feeds `usage_records` → enforces plan limits and powers usage-based billing.
- **Cost guard:** hard monthly token ceiling per tenant; circuit breaker on runaway loops.
- **Asset pipeline:** generate (EN) → auto-translate (ES) → store both versions → optional auto-deploy → trigger Stage 2 learning. Exactly the automation flow from your OPTIMAL_BUILD_ORDER doc, now in Java.

---

## 10. SAAS BILLING & PACKAGING

| Plan | Price (suggested) | Companies | Platforms | AI tokens/mo | Seats |
|---|---|---|---|---|---|
| Starter | $97/mo | 1 | 3 | 500K | 2 |
| Pro | $297/mo | 5 | 10 | 3M | 10 |
| Enterprise | $997/mo | Unlimited | All 15 | 15M + overage | Unlimited |

- **Stripe Checkout** for signup, **Customer Portal** for card/plan changes (zero PCI burden — card data never touches your server).
- **Stripe webhooks** (`checkout.session.completed`, `invoice.payment_failed`, `customer.subscription.deleted`) drive tenant status: active → past_due → suspended.
- **Plan limit enforcement** at the service layer (e.g., creating a 2nd company on Starter returns a friendly upgrade prompt).
- 14-day trial flag on `tenants`, feature flags table for gradual rollouts.

---

## 11. DEVOPS & DEPLOYMENT

- **Docker Compose** for local dev: `backend`, `frontend`, `mysql`, `redis`, `mailhog` — one command (`docker compose up`) gives every developer an identical environment.
- **CI/CD (GitHub Actions):** on every push → compile, unit tests, integration tests (Testcontainers spins up real MySQL), OWASP dependency scan, Docker image build → deploy to staging → manual gate → production.
- **Hosting path:** start on a single VPS or AWS Lightsail (~$20–40/mo) → graduate to AWS ECS/RDS when you have paying customers.
- **Observability:** Spring Boot Actuator + Micrometer → Prometheus/Grafana; Sentry for error tracking; uptime monitor on `/actuator/health`.
- **Backups:** automated nightly MySQL dumps + binlog, tested restore procedure (a backup you've never restored is not a backup).

---

## 12. PHASED BUILD PLAN (20 Weeks to Sellable v1)

### PHASE 0 — Foundation (Weeks 1–2)
- Repos, Docker Compose, Spring Boot skeleton, React/Vite skeleton
- Flyway V1: tenants, users, roles, companies
- Auth complete: register, login, JWT + refresh, password reset, email verification
- React AppShell: sidebar/topbar rebuilt from your HTML, route guards, login flow
- **Exit criteria:** Two users in two different tenants can log in and see empty, isolated dashboards.

### PHASE 1 — Multi-Tenancy + Companies (Weeks 3–4)
- TenantContext + Hibernate tenant filter + tenant-leak test suite
- Multi-Company module: CRUD + 5-step onboarding wizard (Company Info → File Upload → API Setup → AI Strategist → Complete), company switcher in topbar
- Audit log + activity feed (backend events → WebSocket → live feed widget)
- **Exit criteria:** Switching companies changes all data; tenant isolation tests green.

### PHASE 2 — Strange Loop Core (Weeks 5–6)
- Loop tables, 5 stage services (stub logic), LoopOrchestrator, Quartz scheduling
- Strange Loop module UI: live 5-stage visualization with progress bars via WebSocket
- **Exit criteria:** Click "Run Cycle" → watch all 5 stages execute live → iteration stored with insights/gaps JSON.

### PHASE 3 — First Integration: GoHighLevel (Weeks 7–8)
- AES-GCM encryption service; PlatformConnector interface; GHL connector (test connection, pull contacts/campaigns/KPIs, webhook receiver)
- Integrations module UI: connect form, masked keys, traffic-light health, sync logs
- KPI snapshots flowing into `kpi_snapshots`
- **Exit criteria:** Real GHL data appears on the Unified Dashboard, refreshed on schedule with zero clicks.

### PHASE 4 — AI Asset Generator (Weeks 9–11)
- AiService + prompt registry + token accounting
- Email Sequence generation end-to-end: form → AI → EN/ES versions → asset library with versioning → deploy to GHL
- Then fan out to the other 6 asset types using the same pipeline
- **Exit criteria:** Generate a 7-email sequence, review it, deploy it to GoHighLevel from the UI.

### PHASE 5 — AI Strategists (Weeks 12–14)
- Seed 17 agents (4 phases) with system prompts and deliverables
- Session chat UI, context loading from company data, RG-1→RG-4 review gates
- Strategist outputs feed Strange Loop Stage 1 (Innovation)
- **Exit criteria:** Run a Phase 1 Market Analysis agent → approve RG-1 → deliverables saved to the company workspace.

### PHASE 6 — Analytics, Alerts & Auto-Documentation (Weeks 15–16)
- KPI aggregation (Leads, CPL, ROAS, CTR, conversions), Recharts dashboards, PDF report export
- Alert rules engine + channels (email via SES/SendGrid, SMS via Twilio, Slack webhook)
- Stage 3 auto-documentation to Notion/Linear/Confluence
- **Exit criteria:** A CPL spike fires an email + Slack alert; weekly report PDF generates automatically.

### PHASE 7 — SaaS Hardening & Billing (Weeks 17–18)
- Stripe plans, checkout, portal, webhooks, plan-limit enforcement, trial flow
- Rate limiting, security headers, OWASP pass, penetration checklist, load test
- Team management UI (invite users, assign roles), tenant data export
- **Exit criteria:** A stranger can sign up, pay, onboard a company, and use the product with zero help from you.

### PHASE 8 — Expand Platforms + Advanced AI (Weeks 19–20+)
- Connectors #2–#15 (ReachInbox → LinkedIn → Meta → Google Ads → ...), ~2–3 days each using the pattern
- Predictive alerts (trend analysis on kpi_snapshots), optimization suggestions, What-If simulator, AI Co-Pilot chat
- **Exit criteria:** Roadmap Phases 10–13 from your PDF are live.

---

## 13. RISKS & MITIGATIONS

| Risk | Mitigation |
|---|---|
| Tenant data leak | Hibernate-level filter + automated leak tests on every endpoint in CI |
| AI cost blowout | Per-tenant token ceilings, model tiering, circuit breakers |
| Platform API changes (GHL etc.) | Connector interface isolates each platform; contract tests per connector |
| Scope creep (11 modules at once) | Strict phase exit criteria; nothing starts until the previous phase's criteria pass |
| Solo-dev burnout | Phases sized to ship something visible every 2 weeks |

---

## 14. WHAT YOU'LL LEARN ALONG THE WAY (Tutor's Map)

- **Phase 0:** HTTP, REST, JWT, hashing vs encryption, React components & routing
- **Phase 1:** ThreadLocal context, Hibernate filters, WebSockets, event-driven design
- **Phase 2:** Schedulers, state machines, transactional boundaries
- **Phase 3:** API clients, webhooks, HMAC signatures, symmetric encryption
- **Phase 4–5:** Prompt engineering, streaming AI responses, token economics
- **Phase 6–7:** Aggregation queries, alerting patterns, Stripe billing lifecycle, OWASP Top 10
