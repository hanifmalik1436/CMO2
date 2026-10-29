# CLAUDE.md — CMO² Master Command Platform

> Guidance for AI coding assistants (and human developers) working in this repo.
> Read this file fully before writing or modifying any code.

---

## 1. What This Project Is

CMO² ("Chief Marketing Officer, Squared") is a **multi-tenant SaaS marketing
command center**. It gives agencies and multi-brand businesses one control panel
to run, automate, and optimize marketing across 15+ external platforms.

Its signature feature is the **Strange Loop OS** — a 5-stage, self-improving
automation cycle (Innovation → Learning → Documentation → Reporting →
Optimization) that makes the platform act like an autonomous marketing engine.

**Current reality:** the project is being rebuilt from a high-fidelity HTML/CSS/JS
prototype (~20% complete — UI shell only, all data is mocked, no backend exists)
into a production SaaS. **You are building the remaining 80%:** backend, database,
auth, integrations, AI engine, and billing.

---

## 2. The Tech Stack (and the one trap)

| Layer | Choice | Version |
|---|---|---|
| Backend language | Java (LTS) | **17** |
| Backend framework | Spring Boot | 3.3.x |
| Security | Spring Security | 6.x |
| ORM | Spring Data JPA / Hibernate | 6.x |
| DB migrations | Flyway | latest |
| Scheduling | Quartz (via Spring) | latest |
| Rate limiting | Bucket4j (Redis-backed) | latest |
| Real-time | Spring WebSocket (STOMP) | — |
| Build tool | Maven | 3.9+ |
| Frontend | React | **19** |
| Frontend language | TypeScript | 5.x |
| Frontend build | Vite | 5.x |
| Styling | Tailwind CSS + shadcn/ui | latest |
| Server state | TanStack Query | 5.x |
| Client state | Zustand | 4.x |
| Forms/validation | React Hook Form + Zod | latest |
| Charts | Recharts | 2.x |
| HTTP client | Axios | 1.x |
| Database | MySQL | 8.0+ |
| Cache/counters | Redis | 7.x |
| Containers | Docker + Docker Compose | latest |

> ⚠️ **TRAP:** Source requests sometimes say "React 20." **React 20 does not
> exist.** Use **React 19** (current stable). Likewise the backend is **Java +
> Spring Boot**, NOT Node.js — any older Node sketches are superseded.

---

## 3. Architecture: Modular Monolith

One deployable Spring Boot app, split into **strictly bounded internal modules**.
Boundaries are kept clean enough that any module can later become a microservice
without a rewrite. Do **not** introduce microservices, a message broker, or a
second deployable for v1.

### Backend package layout (`com.cmo2`)
```
shared/         config, security, crypto, events, audit, exceptions  (shared kernel)
auth/           login, register, MFA, refresh, password reset
tenancy/        tenants, companies, onboarding wizard state machine
billing/        StripeService, WebhookController, PlanLimitGuard
integrations/   core (PlatformConnector iface) + one folder per platform + webhooks
assets/         GenerationService, templates, Translator, Deployer, versioning
strategists/    AgentRegistry (17 agents), SessionService, ReviewGateService
strangeloop/    orchestrator (LoopOrchestrator) + stages (Stage1..Stage5)
analytics/      KpiIngestor, Aggregator, ReportGenerator (PDF)
alerts/         RuleEngine + EmailChannel, SmsChannel, SlackChannel
ai/             AiService (provider-agnostic), PromptRegistry, TokenAccountant, CostGuard
```
Structure is **package-by-feature**: each module owns its own
`controller / service / repository / dto`. Do not create global "controllers" or
"services" buckets.

### Frontend layout (`cmo2-frontend/src`)
```
app/         AppShell, routes, guards (RequireAuth, RequireRole)
features/    one folder per module, mirroring the backend modules
components/ui/  shadcn primitives + GlassCard, StatBadge, TrafficLight
lib/         api.ts (Axios + silent refresh), ws.ts (STOMP), queryClient.ts
stores/      Zustand: session, selectedCompany, ui prefs
```
**Frontend folders mirror backend modules.** Keep them in sync.

---

## 4. 🔴 NON-NEGOTIABLE RULES

These cause critical bugs or security holes if broken. Treat them as hard
constraints, not suggestions.

1. **TENANT ISOLATION IS SACRED.** Every tenant-owned table has a `tenant_id`
   column. Never write a query, INSERT, or update that bypasses tenant scoping.
   - `tenant_id` is set automatically from `TenantContext` (a ThreadLocal
     populated from the JWT) via a Hibernate tenant filter and an entity
     listener. **Never** accept `tenant_id` from client input.
   - A CI **tenant-leak test suite** authenticates as Tenant A and tries to read
     Tenant B's data on every endpoint. If you add an endpoint, it must pass.
   - Tenant hierarchy: `Tenant → Users / Subscription / Companies → (per company)
     Platform Connections, Assets, Strategist Sessions, Loop Iterations, KPI
     Snapshots, Alerts, Reports`.

2. **SECURITY IS BUILT FIRST, NEVER BOLTED ON.** Auth, RBAC, and tenancy come
   before features. New code inherits the existing security filter chain.

3. **AUTHORIZATION IS ENFORCED AT THE SERVICE LAYER** with method security
   (`@PreAuthorize`). UI role checks are convenience only — never the real
   boundary. Roles: `OWNER > ADMIN > STRATEGIST > ANALYST > VIEWER`.

4. **SECRETS ARE ENCRYPTED.** Customer platform API keys use AES-256-GCM envelope
   encryption (per-tenant data key wrapped by a master key). After save, the UI
   only ever sees `••••last4`. App secrets live in env vars / Docker secrets —
   **never** in code or the repo.

5. **ALL INBOUND WEBHOOKS ARE HMAC-VERIFIED** (Stripe signing secret + platform
   HMACs), persisted raw to `webhook_events`, then processed asynchronously.

6. **SCHEMA CHANGES GO THROUGH FLYWAY ONLY** (`V1__init.sql`, `V2__assets.sql`…).
   Never hand-edit the database or rely on `ddl-auto` in non-dev.

7. **EVERY FEATURE MAPS TO A STRANGE LOOP STAGE (1–5).** If you can't answer
   "which stage does this belong to?", reconsider the design.

8. **AI HAS A BUDGET.** All AI calls route through the provider-agnostic
   `AiService`, which meters tokens per tenant and enforces `CostGuard` ceilings
   (circuit breakers). Never call an AI provider SDK directly from a module.

---

## 5. Key Patterns to Follow

- **Connector pattern (integrations):** one `PlatformConnector` interface, 15
  implementations. GoHighLevel is the reference build; the other 14 replicate it.
  ```java
  public interface PlatformConnector {
      Platform platform();
      ConnectionHealth testConnection(EncryptedCredentials creds);
      SyncResult pullData(SyncRequest request);   // contacts, campaigns, KPIs
      DeployResult deployAsset(Asset asset);       // push generated content
  }
  ```
- **Internal eventing:** modules are decoupled via Spring `ApplicationEvents`.
  E.g. `AssetGeneratedEvent` triggers translation, documentation, deployment, and
  Stage-2 learning — the generator doesn't know those listeners exist. Prefer
  events over direct cross-module calls.
- **Strange Loop orchestration:** `LoopOrchestrator.runCycle()` runs stages 1→5,
  persists each transition to `loop_stage_runs` (so crashed cycles **resume**, not
  restart), emits domain events → WebSocket progress bars, then reschedules via
  Quartz.
- **Real-time:** one authenticated STOMP-over-SockJS socket with topic streams
  `/topic/tenant.{id}.loop`, `.activity`, `.alerts`. No polling.
- **Frontend state:** server data in **TanStack Query**; minimal client state in
  **Zustand**; forms via **React Hook Form + Zod**. Don't add Redux.
- **MySQL hybrid:** relational columns for anything queried/filtered; JSON columns
  (`content_json`, `insights_json`, `limits_json`) for flexible AI payloads.

---

## 6. Build & Run Commands

> Once the standard scaffolding exists, these are the canonical commands. Adjust
> only if the actual scripts differ.

**Whole stack (local dev):**
```bash
docker compose up        # backend, frontend, mysql, redis, mailhog
```

**Backend (Maven):**
```bash
cd cmo2-backend
./mvnw spring-boot:run                  # run app
./mvnw test                             # unit + integration tests (Testcontainers spins real MySQL)
./mvnw verify                           # full verify incl. tenant-leak suite + scans
./mvnw flyway:migrate                   # apply DB migrations
```

**Frontend (Vite):**
```bash
cd cmo2-frontend
npm install
npm run dev                             # dev server with hot reload
npm run build                           # production build
npm run test                            # Vitest + React Testing Library
npm run test:e2e                        # Playwright smoke tests
npm run lint
```

**Docs:** OpenAPI/Swagger UI is auto-generated (springdoc) at `/swagger-ui.html`.
Health probe at `/actuator/health`.

---

## 7. Build Order (Phases — strictly sequential)

No phase starts until the previous phase's **exit criteria (tests, not opinions)**
pass.

| Phase | Theme | Headline deliverable |
|---|---|---|
| 0 | Foundation | Auth + app shells running; CI green |
| 1 | Multi-tenancy + companies | Proven tenant isolation; onboarding wizard |
| 2 | Strange Loop core | Live 5-stage cycle |
| 3 | First integration (GoHighLevel) | Real data on dashboard, auto-syncing |
| 4 | AI asset generation | Generate → translate (EN→ES) → deploy |
| 5 | AI Strategists | 17 agents + review gates RG-1..RG-4 |
| 6 | Analytics, alerts, auto-docs | Anomaly → alert within 5 min; PDF reports |
| 7 | SaaS hardening & billing | Stranger can sign up & pay (the "sellable" gate) |
| 8 | Expansion & advanced AI | Connectors 2–15, predictive AI, What-If sim |

---

## 8. Definition of Done (every feature)

A feature is done **only when all of these hold**:
1. Backend endpoint(s) + service logic implemented **with validation**.
2. Tenant isolation verified by the leak suite.
3. Unit + integration tests passing in CI.
4. Frontend UI wired with **loading / error / empty** states.
5. Audit/activity events emitted.
6. Mapped to its Strange Loop stage.
7. Documented (auto-doc where applicable).

---

## 9. Conventions Cheat-Sheet

- **Java:** package-by-feature; use records/sealed classes where they simplify;
  Lombok for boilerplate; MapStruct for entity↔DTO; Bean Validation on all
  request DTOs.
- **TypeScript:** strict mode; types/zod schemas mirror backend validation.
- **Naming:** backend modules and frontend `features/` folders share names.
- **Never** disable tenant filtering, security headers, or HMAC checks "just to
  test" — use proper test fixtures instead.
- **Errors:** backend uses a single `GlobalExceptionHandler` (`@ControllerAdvice`);
  frontend surfaces friendly messages, never raw stack traces.

---

## 10. External Services (reference)

AI: Anthropic Claude (strategy/reasoning), OpenAI (bulk/translation tier) — both
behind `AiService`. Billing: Stripe (hosted Checkout/Portal — no card data on our
servers). Email: SendGrid/SES. SMS: Twilio. Alerts: Slack incoming webhooks.
Auto-docs: Notion / Confluence / Linear. Connectors: GoHighLevel (first),
then ReachInbox, LinkedIn, Meta, Google Ads, ManyChat, Nureply, Sendr, Leadpro,
SlyBroadcast, TikTok, Stripe(data), PayPal.
