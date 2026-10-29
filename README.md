# CMO² Master Command Platform

Multi-tenant SaaS marketing command center. This repository is being built from the
planning docs (`01`–`05` + `CMO2_SPRINGBOOT_REACT_SAAS_PLAN.md`) following the
strictly-sequential phase plan in `CLAUDE.md`.

## What is implemented now (Phases 0–8 — the full v1 build)

This is the **foundation + Strange Loop engine + first live integration + AI asset generation +
the AI Strategist Council + analytics, alerts & auto-documentation + SaaS billing & hardening
(the "sellable" gate) + the full connector fleet, forecasting & What-If simulation**:

- ✅ **Backend skeleton** — Spring Boot 3.3 (Java 17), Maven, OpenAPI/Swagger, Actuator health.
- ✅ **Auth** — register, login, JWT access + rotating refresh tokens, BCrypt(12), account lockout.
- ✅ **Multi-tenancy** — `tenant_id` discriminator on every tenant-owned table, JWT `tenant_id`
  claim → `TenantContext` (ThreadLocal) → Hibernate `@Filter` auto-scoping + entity listener
  auto-stamping. Clients can never supply `tenant_id`. Enforced on the WebSocket channel too.
- ✅ **RBAC** — `OWNER > ADMIN > STRATEGIST > ANALYST > VIEWER`, enforced at the service layer via `@PreAuthorize`.
- ✅ **Companies** — CRUD scoped to the tenant (the Multi-Company module's data backbone).
- ✅ **Strange Loop OS (Phase 2)** — the live 5-stage cycle (Innovation → Learning →
  Documentation → Reporting → Optimization). Async orchestrator, each stage transition
  persisted to `loop_stage_runs` so a **crashed cycle resumes** (not restarts), real-time
  progress over **STOMP/SockJS** (`/topic/tenant.{id}.loop`), iteration history with
  insights/gaps JSON, and auto-rescheduling of the next cycle. *(Stage bodies are honest
  stubs — they produce real structured output but say "no KPI data yet"; Phases 3–5 fill them in.)*
- ✅ **GoHighLevel integration (Phase 3)** — the `PlatformConnector` pattern (one interface,
  GoHighLevel as the reference build), **AES-256-GCM-encrypted** API keys (UI only ever sees
  `••••last4`), connection test with **traffic-light health**, a background scheduler that
  syncs KPIs into `kpi_snapshots` with **zero clicks**, manual "Sync now", per-connection
  **sync logs**, and **HMAC-verified inbound webhooks** persisted raw to `webhook_events`.
  *Ships with a **demo mode** (default on) that returns realistic, time-varying KPIs so the
  full connect → sync → dashboard pipeline runs without a live GHL account.*
- ✅ **Live dashboard** — the KPI strip shows real latest values per company and auto-refreshes.
- ✅ **AI Asset Generator (Phase 4)** — provider-agnostic `AiService` with **per-tenant token
  metering** and a **`CostGuard`** circuit breaker enforcing each plan's monthly token ceiling
  (NON-NEGOTIABLE #8). Pipeline: generate (EN) → **auto-translate to ES** → versioned, deployable
  asset → **deploy to GoHighLevel**, all decoupled via an `AssetGeneratedEvent`. Ships a **stub
  provider** (default) so it runs with no API key, plus a real **`AnthropicProvider`** built on the
  official Anthropic Java SDK (latest Claude models, tiered: generation/strategy = Opus 4.8,
  translation = Haiku 4.5).
- ✅ **AI Strategist Council (Phase 5)** — a seeded council of specialist agents across the 4
  phases (Insights & Brand · Distribution · Big Media & Content · Sales & Ops), each with a
  system prompt + deliverable schema. Per-company **sessions** (chat + structured deliverables,
  all metered through `AiService`), **review gates RG-1…RG-4** with role-restricted approval, and
  approved deliverables that **flow into Strange Loop Stage 1 (Innovation)**. *(Doc 05 enumerates
  19 agents; the overview headlines "17" — both noted in `AgentSeeder`.)*
- ✅ **Analytics, Alerts & Auto-Documentation (Phase 6)** — **Analytics**: per-company KPI
  **time-series** + a **portfolio roll-up** across the tenant's companies (tenant-scoped reads).
  **Alerts**: per-metric threshold **rules** (`>`/`≥`/`<`/`≤`, severity, multi-channel) evaluated by
  a continuous **`RuleEngine`** sweep so a breach fires **well within the 5-minute target**;
  delivery across **email** (Mailhog/SES), **Slack** (incoming webhook), **SMS** & **in-app**, with
  open-alert de-duplication, an **alert center** with acknowledge, and a **"simulate spike"** path
  that exercises the exact firing code instantly. **Reports**: leadership **PDF** generation
  (OpenPDF) for a company or the whole portfolio, stored in the Reports module and **downloadable**,
  plus a **weekly scheduler** that auto-generates a portfolio PDF per tenant. **Auto-docs**: Strange
  Loop **Stage 3** now publishes iteration insights via a config-gated `DocumentationService`
  (Notion/Linear keys → publish; blank → record intent, loop still completes).
- ✅ **SaaS billing & hardening (Phase 7 — the "sellable" gate)** — **Stripe** billing behind a
  provider-agnostic `BillingGateway` (a **stub gateway is the default**, so the whole
  **sign-up → pay → use** flow runs with no Stripe account; set `cmo2.billing.provider=stripe`
  for hosted **Checkout + Customer Portal**, card data never touching our servers). A
  **webhook-driven lifecycle** (`checkout.session.completed` / `invoice.payment_failed` /
  `customer.subscription.deleted`) moves a tenant **trial → active → past_due → canceled**, with
  Stripe signatures verified and events persisted to `webhook_events` (NON-NEGOTIABLE #5).
  **`PlanLimitGuard`** enforces companies / connections / seats / AI-tokens **server-side** with a
  friendly **402 upgrade prompt** (and AI tokens via the existing `CostGuard`). **Team
  management** — invite by email (seat-limited), accept-invite → set password, role changes
  (owner-only, last-owner protected), deactivate/reactivate. **Hardening** — per-tenant/IP
  **rate limiting** (Bucket4j), **CSP + HSTS** security headers, and a **tenant data-export**
  endpoint (GDPR groundwork).
- ✅ **Expansion & advanced AI (Phase 8)** — the **full connector fleet** (14 platforms): every
  platform in the catalog is connectable (GoHighLevel's live+demo reference plus **13 expansion
  connectors** —
  ReachInbox, Nureply, Sendr, Leadpro, ManyChat, SlyBroadcast, LinkedIn, Facebook, Instagram,
  Google Ads, TikTok, Stripe-data, PayPal) behind one `PlatformConnector` interface, each with a
  platform-appropriate KPI profile and **demo mode on by default** (`GET /api/integrations/platforms`
  drives the connect UI's platform picker). **Predictive analytics** — a `ForecastService` projects
  any KPI forward with an OLS trend fit, returning the projection, trend, R²-confidence, and a
  leadership-readable narrative. **What-If simulator** — a transparent elasticity model projects how
  spend / CPL / conversion changes ripple through leads → appointments → ROAS → revenue.
- ✅ **Flyway V1–V7** — identity/tenancy/companies → loop tables → integrations/KPIs →
  assets/usage → strategist council → analytics/alerts/reports → billing (tenant Stripe
  subscription fields). *(Phase 8 added no schema — connectors reuse `platform_connections` /
  `kpi_snapshots`; forecasting & What-If are read-only over existing data.)*
- ✅ **Frontend shell** — React 19 + Vite + TypeScript + Tailwind, AppShell, login/register,
  route guards, live dashboard, companies, **live Strange Loop visualization**,
  **Integrations Hub** (connect/test/sync/health/logs), **AI Asset Generator** (type picker,
  brief, EN/ES toggle, preview, deploy, live token meter), **AI Strategist Council** (council by
  phase, session chat, deliverables, review gates), **Analytics** (Recharts KPI trends + portfolio
  strip), **Alerts** center (rule builder, alert list + acknowledge, simulate-spike), **Reports**
  (generate per-company/portfolio + download PDF), **Billing** (plan cards, usage meters, upgrade
  via checkout/demo, manage-billing portal), **Settings** (team invite/roles/deactivate + data
  export), **accept-invite** onboarding, an **all-platforms** connect picker, and **forecast
  overlay + What-If simulator** on Analytics. Axios silent refresh, TanStack Query, Zustand, STOMP client.
- ✅ **Docker Compose** — backend, frontend, MySQL 8, Redis 7, Mailhog.

## v1 is feature-complete — what's left is going live

All eight build phases (P0–P8) are implemented and tested. The remaining work is **flipping
demo/stub surfaces to live providers** — no new architecture required, because each was built
behind an interface with a working default:

- **AI** — `CMO2_AI_PROVIDER=stub` → `anthropic` (real Claude) via `AiService`.
- **Billing** — `cmo2.billing.provider=stub` → `stripe` (hosted Checkout/Portal + webhooks).
- **Connectors** — `CMO2_INTEGRATIONS_DEMO_MODE=true` → wire each platform's live API (GoHighLevel
  is the reference live path; the other 13 replicate it per-connector).
- **Alert/report transports** — Mailhog + Slack webhooks today → hosted SES/SendGrid/Twilio.

The seams are already in place: Strange Loop Stage 1 consumes approved strategist output, the asset
pipeline's `AssetGeneratedEvent` is where Stage-2 learning attaches, and every connector implements
the same `PlatformConnector` contract.

### AI: stub vs. real Claude
The Asset Generator defaults to a **stub provider** (`CMO2_AI_PROVIDER=stub`) that returns
realistic demo copy with no API key — so the whole generate → translate → deploy flow works out
of the box. For real generation, set `CMO2_AI_PROVIDER=anthropic` and provide `CMO2_AI_API_KEY`
(or `ANTHROPIC_API_KEY`); models are configurable per tier (`CMO2_AI_MODEL_GENERATION` etc.,
defaulting to Claude Opus 4.8 / Haiku 4.5).

### GoHighLevel: demo vs. live
Demo mode is on by default (`CMO2_GHL_DEMO_MODE=true`) so anyone can try the full pipeline —
connect with any key (e.g. `demo-key-123456`), hit **Sync now**, watch KPIs appear. For real
data, set `CMO2_GHL_DEMO_MODE=false` and provide a real GoHighLevel API key.

### Billing: stub vs. real Stripe
Billing defaults to a **stub gateway** (`CMO2_BILLING_PROVIDER=stub`) so the full
**sign-up → pay → use** flow works with no Stripe account: on the **Billing** page, "checkout"
activates the chosen plan instantly (it simulates the `checkout.session.completed` webhook the
real flow would fire). For production, set `CMO2_BILLING_PROVIDER=stripe`, provide
`CMO2_BILLING_STRIPE_SECRET_KEY` + `CMO2_BILLING_STRIPE_WEBHOOK_SECRET`, create the three
products/prices in Stripe and set `CMO2_BILLING_PRICE_{STARTER,PRO,ENTERPRISE}` — checkout and the
customer portal then run on Stripe-hosted pages (no card data touches the server), and webhooks
drive `trial → active → past_due → canceled`. Plan limits (companies, connections, seats, AI
tokens) are enforced server-side in **both** modes, returning a friendly 402 upgrade prompt.

### Connectors & advanced AI (Phase 8)
All **14 platforms** in the catalog are connectable today. **Demo mode is on by default**
(`CMO2_INTEGRATIONS_DEMO_MODE=true`), so any key starting with `demo` returns realistic,
platform-appropriate KPIs — ad platforms report CPL/ROAS, email tools report CTR/conversion,
payment platforms (Stripe/PayPal) report revenue/ROAS as data sources. The connect dialog's
platform picker is driven by `GET /api/integrations/platforms`. On **Analytics**, toggle
**Forecast** to project any KPI forward (OLS trend + confidence + narrative), and use the
**What-If simulator** to model how spend / CPL / conversion changes flow through to revenue. Both
read only existing KPI history, so they work the moment a connector has synced a few times.

## Prerequisites

### Software

| Software | Version | Status | Needed for |
|---|---|---|---|
| **Docker Desktop** / Docker Engine + Compose v2 | latest | **Mandatory for Path A** · Optional for Path B | Path A runs the whole stack; Path B uses it only to spin up MySQL/Redis/Mailhog |
| **JDK 17** (Temurin/Adoptium) | 17 (LTS) | **Mandatory for Path B** (backend) | Compiling/running the Spring Boot backend natively |
| **Maven** | 3.9+ | **Mandatory for Path B** (backend) | Building/running/testing the backend (no `./mvnw` wrapper committed yet) |
| **Node.js + npm** | Node 20+ | **Mandatory for Path B** (frontend) | Building/running the React frontend natively |
| **Git** | latest | Optional | Cloning the repo / version control |
| **A modern browser** | latest | Mandatory | Using the app at `localhost:5173` |

> With **Path A (Docker)** you need *only* Docker — JDK, Maven, and Node all run inside containers.

### External accounts / API keys

The current **Phase 0 + Phase 1 foundation needs no external accounts** — you can sign up,
log in, and manage companies with nothing but the software above. The accounts below are
introduced by later phases (see `03_HIGH_LEVEL_PLAN.md`); list provided so you can plan ahead.

| Account / service | Status (today) | Becomes required in | Used for |
|---|---|---|---|
| *(none)* | — | — | The foundation runs fully offline/local |
| **Anthropic Claude API** | Optional | Phase 4–5 | AI asset generation & strategist agents (frontier reasoning) |
| **OpenAI API** | Optional | Phase 4 | Bulk content + EN→ES translation tier (provider-agnostic `AiService`) |
| **GoHighLevel** | Optional | Phase 3 | First platform connector (reference integration) |
| **Stripe** | Optional (stub default) | Phase 7 for *real* payments | Subscription billing — hosted Checkout/Portal + webhooks; the stub gateway runs the full flow without it |
| **SendGrid / Amazon SES** | Optional | Phase 6 | Production transactional + alert email (Mailhog covers local dev) |
| **Twilio** | Optional | Phase 6 | SMS alerts |
| **Slack (incoming webhook)** | Optional | Phase 6 | Team alert channel |
| **Notion / Confluence / Linear** | Optional | Phase 6 | Strange Loop Stage-3 auto-documentation |
| **Meta / LinkedIn / Google Ads / TikTok / etc.** | Optional (demo default) | Phase 8 for *live* KPIs | Connectors #2–#15 — all connectable in demo mode without keys |

> When a phase needs one of these, its key is supplied via **environment variables / Docker
> secrets** — never committed to the repo (see *Security notes*).

## Run it locally

You have two paths. **Path A (Docker)** needs nothing but Docker installed. **Path B
(native)** runs the backend and frontend directly on your machine and is best for active
development (fast hot-reload, debugger attach).

> **Demoing for a client?** See [DEMO_RUNBOOK.md](DEMO_RUNBOOK.md) — one command brings up a fully-populated demo tenant (login `owner@demo.cmo2` / `demo1234`), no API keys required.

### Ports & URLs (both paths)

| Service | URL |
|---|---|
| Frontend (SPA) | http://localhost:5173 |
| Backend API | http://localhost:8080 |
| Swagger UI (API docs) | http://localhost:8080/swagger-ui.html |
| Health probe | http://localhost:8080/actuator/health |
| Mailhog (catches verification emails) | http://localhost:8025 |

---

### Path A — Everything via Docker (simplest)

**Prerequisites:** Docker Desktop (or Docker Engine + Compose v2).

```bash
# from the repo root
docker compose up --build
```

This starts backend, frontend, MySQL 8, Redis 7, and Mailhog. First build takes a few
minutes (Maven + npm download dependencies). When you see the backend log
`Started Cmo2Application`, open http://localhost:5173.

```bash
docker compose down        # stop
docker compose down -v     # stop and wipe the MySQL volume (fresh DB next start)
docker compose logs -f backend   # tail backend logs
```

---

### Path B — Run natively (recommended for development)

**Prerequisites:**
- **JDK 17** (Temurin/Adoptium recommended) — verify with `java -version`
- **Maven 3.9+** — verify with `mvn -v`  *(no `./mvnw` wrapper is committed yet)*
- **Node.js 20+** and npm — verify with `node -v`
- **Docker** *(optional)* — only to run MySQL/Redis/Mailhog; skip if you have your own MySQL

#### 1. Start the infrastructure (MySQL + Redis + Mailhog)

Easiest is to let Compose run just those three:

```bash
docker compose up -d mysql redis mailhog
```

<details>
<summary>No Docker? Use your own MySQL instead</summary>

Create the database and user the backend expects (defaults from `application.yml`):

```sql
CREATE DATABASE cmo2 CHARACTER SET utf8mb4;
CREATE USER 'cmo2'@'%' IDENTIFIED BY 'cmo2pw';
GRANT ALL PRIVILEGES ON cmo2.* TO 'cmo2'@'%';
FLUSH PRIVILEGES;
```

Redis and Mailhog are optional for the foundation (nothing fails without them — mail just
logs a warning). Point at a different DB/host with the env vars in step 2.
</details>

#### 2. Run the backend (terminal 1)

```bash
cd cmo2-backend
mvn spring-boot:run
```

Flyway applies `V1__init.sql` automatically on first start. The API is up when the log
shows `Started Cmo2Application` — check http://localhost:8080/actuator/health (`{"status":"UP"}`).

The backend reads config from `cmo2-backend/src/main/resources/application.yml`, which
already defaults to `localhost` MySQL/Redis. Override any of these via environment variables
if your setup differs:

```bash
# examples — only set what you need to change
export SPRING_DATASOURCE_URL="jdbc:mysql://localhost:3306/cmo2?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
export SPRING_DATASOURCE_USERNAME=cmo2
export SPRING_DATASOURCE_PASSWORD=cmo2pw
export SPRING_DATA_REDIS_HOST=localhost
# In a shared/staging env, ALWAYS override these dev defaults:
export CMO2_JWT_SECRET="<a long random string, 64+ chars>"
export CMO2_CRYPTO_MASTER_KEY="<base64 of exactly 32 random bytes>"
```
> On Windows PowerShell use `$env:NAME = "value"` instead of `export`.

Run the test suite (uses in-memory H2 — **no MySQL needed**), including the tenant-leak suite:

```bash
mvn test
```

#### 3. Run the frontend (terminal 2)

```bash
cd cmo2-frontend
cp .env.example .env.local      # sets VITE_API_BASE_URL=http://localhost:8080
npm install                     # first time only
npm run dev
```

Vite serves http://localhost:5173 with hot reload and proxies API calls to the backend in
terminal 1. Other useful scripts:

```bash
npm run build     # type-check (tsc) + production build into dist/
npm run lint
```

---

## Configuration reference (environment variables & API keys)

All configuration is via environment variables. A complete, commented template lives in
[`.env.example`](.env.example) — copy it to `.env` and fill in real values
(`cp .env.example .env`). The frontend reads only `VITE_*` vars (from
`cmo2-frontend/.env.local`).

**Active today (Phases 0–3)** — dev defaults exist, but override the two secrets in any shared env:

| Variable | Required | Default (dev) | Purpose |
|---|---|---|---|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | for non-Docker | localhost MySQL | Database connection |
| `SPRING_DATA_REDIS_HOST` / `_PORT` | no | localhost:6379 | Redis |
| `SPRING_MAIL_HOST` / `_PORT` | no | localhost:1025 | SMTP (Mailhog locally) |
| `CMO2_JWT_SECRET` | **yes (prod)** | dev placeholder | JWT signing key — 64+ random chars (`openssl rand -base64 64`) |
| `CMO2_CRYPTO_MASTER_KEY` | **yes (prod)** | dev placeholder | AES-256 key — **Base64 of exactly 32 bytes** (`openssl rand -base64 32`) |
| `CMO2_CORS_ALLOWED_ORIGINS` | no | `http://localhost:5173` | Allowed browser origins |
| `CMO2_LOOP_STAGE_DELAY_MS` / `CMO2_LOOP_RESCHEDULE_SECONDS` | no | 600 / 86400 | Strange Loop timing |
| `CMO2_SYNC_INTERVAL_MS` / `CMO2_SYNC_INITIAL_DELAY_MS` | no | 60000 / 20000 | Background KPI sync cadence |
| `CMO2_GHL_DEMO_MODE` | no | `true` | GoHighLevel demo data vs. live API |
| `CMO2_GHL_BASE_URL` | no | `https://rest.gohighlevel.com` | GHL API base |
| `CMO2_GHL_WEBHOOK_SECRET` | no | — | HMAC secret for inbound GHL webhooks |
| `CMO2_AI_PROVIDER` | no | `stub` | `stub` (demo, no key) or `anthropic` (real Claude) |
| `CMO2_AI_API_KEY` / `ANTHROPIC_API_KEY` | only if `provider=anthropic` | — | Anthropic API key |
| `CMO2_AI_MODEL_GENERATION` / `_TRANSLATION` / `_STRATEGY` | no | opus-4-8 / haiku-4-5 / opus-4-8 | Per-tier Claude model IDs |
| `VITE_API_BASE_URL` (frontend) | no | `http://localhost:8080` | Backend URL the SPA calls |

**External service API keys — not consumed yet** (placeholders/hints for upcoming phases; set
them when you reach the phase):

| Variable(s) | Phase | For |
|---|---|---|
| `OPENAI_API_KEY` | 4–5 | Optional second AI provider (bulk/translation tier) |
| `SENDGRID_API_KEY` / `AWS_SES_*`, `TWILIO_*`, `SLACK_WEBHOOK_URL` | 6 | Real email / SMS / Slack alerts |
| `NOTION_API_KEY`, `LINEAR_API_KEY`, `CONFLUENCE_*` | 6 | Strange Loop Stage-3 auto-documentation |
| `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY`, `STRIPE_WEBHOOK_SECRET` | 7 | Subscription billing |
| `META_GRAPH_ACCESS_TOKEN`, `LINKEDIN_ACCESS_TOKEN`, `GOOGLE_ADS_DEVELOPER_TOKEN`, `TIKTOK_ACCESS_TOKEN`, … | 8 | Connectors #2–#15 |

> ⚠️ Never commit real secrets. The dev defaults in `docker-compose.yml` / `application.yml`
> are for local use only; supply real values via environment or Docker secrets.

---

## First steps after start

1. Open http://localhost:5173 and click **Register** — this creates a new **tenant** + an
   **OWNER** user, and signs you in.
2. (Optional) Open http://localhost:8025 (Mailhog) to see the verification email; click its
   link to verify the address.
3. You land on an empty, tenant-isolated dashboard. Add a couple of **Companies**.
4. Open **Integrations**, pick a company, **+ Connect** GoHighLevel (any key in demo mode, e.g.
   `demo-key-123456`), then **Sync now** — KPIs appear on the **Dashboard** and keep refreshing
   on the background schedule.
5. Open **Asset Generator**, pick a type (e.g. Email Sequence), click **✦ Generate** — preview the
   **EN** and **ES** versions, watch the token meter tick up, then **Deploy to GHL**.
6. Open **AI Strategists**, run **Market Analysis** → **Produce deliverables** (TAM/SAM/SOM…),
   then **Approve RG-1**. Now run a **Strange Loop** cycle and inspect Stage 1 — it folds in the
   approved deliverables (`"source":"strategist:approved"`).
7. Open **Analytics** to see the portfolio KPI strip and per-company trend chart. Open **Alerts**,
   create a rule (e.g. *CPL > 50*, channels Email + Slack), then click **⚡ Simulate spike** —
   watch the alert fire in the **alert center** (showing the channels it was sent via) and
   **acknowledge** it. Open **Reports**, **Generate** a company PDF, and **Download** it. (A weekly
   portfolio PDF is also produced automatically per tenant.)
8. Open **Billing**. You start on **Starter / trial**; the usage meters track companies,
   connections, seats and AI tokens. Try adding a 2nd company on Starter — you'll get a friendly
   **upgrade prompt** (402). Click **Upgrade** to **Pro** (in demo mode it activates instantly), and
   the limit lifts. Open **Settings** to **invite** a teammate (they get an accept-invite email in
   Mailhog), change roles, or **Export data** as a JSON bundle.
9. Back in **Integrations**, **+ Connect** another platform from the picker (e.g. **Google Ads** or
   **Meta**) with a `demo-…` key and **Sync** — its KPIs flow into the dashboard too. On
   **Analytics**, toggle **Forecast** to project a KPI forward, then open the **What-If simulator**
   and try *spend +20% / CPL −10%* to see the revenue impact.
10. Register a **second account** in an incognito window — confirm the two tenants cannot see each
    other's companies, connections, KPIs, assets, strategist sessions, loop cycles, alerts, reports,
    billing, or team. (This is also what `mvn test`'s `TenantIsolationTest`, `IntegrationSyncTest`,
    `AssetGenerationTest`, `StrategistCouncilTest`, `AnalyticsAlertsReportsTest`,
    `BillingTeamExportTest`, `ExpansionAndPredictiveTest`, and `StrangeLoopCycleTest` prove.)

> **The "sellable" gate (and beyond):** a stranger can sign up, pay, onboard a company, connect any
> platform in the catalog, generate & deploy an asset, run a loop, and forecast/simulate outcomes —
> with zero assistance.

## Troubleshooting

- **`java`/`mvn` not found** → install JDK 17 + Maven 3.9, or use Path A (Docker).
- **Backend fails with `Communications link failure` / `Access denied`** → MySQL isn't up yet
  or credentials differ; ensure `docker compose up -d mysql` is healthy, or fix the
  `SPRING_DATASOURCE_*` vars.
- **`Flyway validate failed` / schema mismatch** → you have an old DB; reset it with
  `docker compose down -v` (Docker) or `DROP DATABASE cmo2; CREATE DATABASE cmo2;`.
- **Frontend can't reach the API / CORS error** → confirm the backend is on `:8080` and
  `VITE_API_BASE_URL` in `.env.local` matches. Allowed origins are set by
  `CMO2_CORS_ALLOWED_ORIGINS` (defaults to `http://localhost:5173`).
- **Port already in use** → stop whatever holds `5173`/`8080`/`3306`, or change the mapped
  ports in `docker-compose.yml`.

## Security notes

- Dev secrets in `docker-compose.yml` are **for local use only**. Provide real values via
  environment / Docker secrets in any shared environment.
- Schema changes go through **Flyway only** (`src/main/resources/db/migration`). Never hand-edit the DB.
