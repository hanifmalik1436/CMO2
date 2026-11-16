# CLAUDE.md — CMO²

> Guidance for AI coding assistants and developers working in this repo. Read it fully before
> writing or changing code. For what is built, what is unverified and what comes next, read
> **`docs/PROJECT_STATE.md`**. The previous version of this file (the original SaaS plan) is
> `deprecated-Claude.md`; don't follow it.

---

## 1. What this project is

CMO² is the **agency owner's private console** between the owner's white-labelled
GoHighLevel (GHL) agency and that agency's customers.

- **Customers never log in to CMO².** They only see their GHL sub-account.
- **The owner (OWNER) and a partner (ADMIN) run everything.** CMO² lists the agency's
  sub-accounts; each one the owner enables becomes a **Company** (a "customer").
- For each customer, the **Strange Loop** runs on its own daily, weekly or monthly schedule:
  1. **Innovation:** plan the cycle with AI, draft social posts.
  2. **Learning:** read the week's figures, measure earlier experiments.
  3. **Documentation:** write the cycle's record, which is the "Close".
  4. **Reporting:** gaps.
  5. **Optimization:** propose experiments.
- The next cycle starts by loading that record plus the owner's edits in Notion, Confluence and
  Linear (the "Boot").
- **Nothing client-facing leaves CMO² without the owner's approval.**

It is not a self-serve SaaS: there is no public sign-up (`CMO2_SELF_REGISTRATION=first-user-only`).

---

## 2. Stack (as used in the code)

| Layer | Choice |
|---|---|
| Backend | Java 17, Spring Boot 3.3.5, Spring Security 6 (JWT), Spring Data JPA / Hibernate 6 |
| Database | MySQL 8 with **Flyway** (`V1`–`V16`); tests run on **H2** |
| Scheduling | **Quartz** (JDBC job store in MySQL, in-memory in tests); Spring `@Scheduled` for syncs and sweeps |
| Real-time | Spring WebSocket / STOMP (loop progress) |
| AI | Anthropic Java SDK 2.34 (Claude); OpenAI Chat Completions over `RestClient`; both behind `AiService` |
| Other | Bucket4j rate limiting (**in-memory**), OpenPDF (reports), springdoc (Swagger), Lombok |
| Frontend | React 19, TypeScript 5.9 (strict), Vite, Tailwind, TanStack Query 5, Axios, React Router, Recharts, Zustand (session only), React Hook Form + Zod, STOMP client |

Present but **not used**: MapStruct (DTOs use static `of(...)` factories instead) and shadcn/ui (the only
shared UI components are `GlassCard` and `TrafficLight`). Redis is configured but not required to run.

---

## 3. Architecture: modular monolith, package by feature

One Spring Boot app. Each module owns its `controller / service / repository / domain / dto`.
Don't add microservices, a message broker or global "services" folders.

### Backend modules (`cmo2-backend/src/main/java/com/cmo2/`)

| Module | Owns |
|---|---|
| `shared` | security (JWT, `TenantContext`), tenancy (`BaseTenantEntity`, filter, `TenantGuard`), crypto (`AesGcmService`), audit, exceptions (`GlobalExceptionHandler`), rate limit, mail, `TeamNotifier`, demo seeder |
| `auth` | login, register (first user only), refresh, team/invites, roles |
| `tenancy` | tenants, companies (customers), timezone |
| `customers` | customer profile, GHL facts, **identity block** for every AI call |
| `integrations` | `gohighlevel/` (`GhlGateway`, OAuth, agency, `GhlLocationReader`, `GhlSocialPlanner`, `GhlCustomValues`, `capability/`), sync, webhooks |
| `analytics` | KPI snapshots, **daily rollups**, week-over-week trends, forecast / what-if |
| `assets` | generation (EN + ES), **approvals**, publishing (`Deployer`), asset activity |
| `strangeloop` | `orchestrator/` (runner, resume), `stages/` 1–5, `schedule/` (Quartz), `memory/` (Boot/Close, cycle records), `learning/`, `experiments/` |
| `docs` | the owner's Notion / Confluence / Linear / Slack: clients, connections, per-customer targets, `DocumentationService`, `LinearTaskService` |
| `ai` | `AiService`, `AiRouter`, providers, `PromptRegistry`, `JsonSchemas`, budgets, AI call log |
| `alerts`, `reports` | KPI alert rules and PDF reports |
| `strategists`, `billing` | **Legacy** from the original SaaS plan (17 strategist agents; Stripe billing for CMO² itself). Keep working; don't extend without a reason. |

### Frontend (`cmo2-frontend/src/`)
`app/` (shell, routes, `RequireAuth`, `RequireRole`), `features/` (one folder per module:
`customers`, `assets` incl. Approvals, `strangeLoop`, `integrations`, `settings`, …),
`lib/` (`api.ts` with silent refresh, `types.ts`, `ws.ts`), `stores/session.ts`.
Frontend features mirror backend modules.

---

## 4. Non-negotiable rules

1. **Tenant isolation.** Every tenant-owned entity extends `BaseTenantEntity` (`tenant_id`
   stamped from `TenantContext`). Never take `tenant_id` from a request.
   - **Request paths:** load by id with `TenantGuard.owned(repo.findById(id))`. `findById` bypasses
     the Hibernate filter.
   - **Background threads** (loop stages, Quartz jobs, sync, event listeners) can't rely on the
     filter: use repository methods that name the tenant (`findByTenantIdAnd…`) and pass `tenantId`
     explicitly.
   - Every new endpoint gets a test where another agency gets **404**.
2. **Authorization at the service layer** with `@PreAuthorize` (roles `OWNER > ADMIN > STRATEGIST >
   ANALYST > VIEWER`). Anything client-facing or that changes GHL is OWNER/ADMIN. Methods for
   background callers have no `@PreAuthorize` and say "Internal" in their Javadoc. UI role checks
   are convenience only.
3. **Secrets.**
   - Customer and workspace credentials are encrypted with `AesGcmService`; the UI only sees
     `••••last4`.
   - App secrets come from env vars.
   - Never commit `application.properties`, `.env`, `.env.*` or `_retired_backup_*/`; all are
     git-ignored.
4. **Webhooks are verified before they're trusted**: GHL with Ed25519 (`X-GHL-Signature`), Stripe
   with its signing secret. They are persisted raw to `webhook_events`.
5. **Schema changes only through Flyway.** The next migration is **`V17__…sql`**. Hibernate runs
   with `ddl-auto=validate`, and tests build the schema from entities, so entity mappings must match
   the SQL exactly:
   - use `@Column(columnDefinition = "LONGTEXT")`, never `@Lob`
   - use `INT` columns for `Integer` and `BIT(1)` for `boolean`
   - avoid reserved column names like `value` (`SchemaConventionsTest` checks this)
6. **Every feature maps to a Strange Loop stage (1–5).**
7. **AI goes only through `AiService`.**
   - Open an `AiCallScope` (`forCompany` / `forStage`) so the call is recorded against the
     customer and counted in its monthly budget.
   - Put prompts in `PromptRegistry`.
   - For structured answers use `AiService.generateJson(request, schema)` with schemas built by
     `JsonSchemas`. Enum values limit the AI to known metrics, actions and ids; the full schema is
     validated, with one repair attempt.
   - Text from outside (customer-written GHL data, the owner's notes, anything from Notion,
     Confluence or Linear) goes in prompts as **data, never instructions**: label it (`DATA:`
     sections, the identity block's framing) and flatten single-line fields.
8. **GHL access only through `GhlGateway`.** Actions that change anything go through the capability
   registry (`GhlCapability` + typed `GhlAction` records) and `GhlActionExecutor`:
   - `READ` and `REVERSIBLE_WRITE` run directly.
   - `CLIENT_FACING` needs an `approvalRef` (e.g. `asset:42`).
   - `DESTRUCTIVE` is humans only, never `Actor.AGENT`.
   - **GET requests are retried; POSTs never are.** An unanswered create raises
     `GhlPostUnconfirmedException` and needs a person to confirm a retry.
9. **Nothing client-facing without approval.** Drafts start `PENDING_APPROVAL`; only `APPROVED`
   assets can be published. Experiments that change GHL wait for the owner unless
   `cmo2.loop.auto-apply-reversible=true`.

---

## 5. Patterns to follow

- **Events between modules** (Spring `ApplicationEvent`s) instead of direct cross-module writes.
  For listeners that write after the event's transaction:
  - `@TransactionalEventListener(fallbackExecution = true)`
  - catch exceptions in the listener
  - do the work in a service method with `@Transactional(propagation = REQUIRES_NEW)`

  Examples: `CustomerFactsSyncedEvent`, `AssetReviewedEvent` / `AssetPublishedEvent` (they drive
  experiments), `CompanyTimezoneChangedEvent` (moves the Quartz trigger).
- **Strange Loop run** (`LoopRunner`):
  - Boot (`CycleMemoryService.boot`) puts `memory` into `StageContext`.
  - Each stage runs inside `AiCallScope.forStage` and is persisted to `loop_stage_runs`.
  - A crashed cycle **resumes** at the first unfinished stage, so stages must tolerate missing
    context (e.g. Stage 4 fetches trends itself) and Close must not write twice.
- **Schedules:** the `loop_triggers` row is the source of truth. `LoopScheduleService` keeps
  Quartz in step at startup and on timezone change. `LoopCycleJob` binds the tenant, skips if a
  cycle is still running.
- **Integration code never throws into a cycle:** the docs clients and `GhlLocationReader`
  degrade, and the record or health message names what failed.
- **Figures:** KPIs are computed in Java (rollups, week-over-week). AI only reads compact
  summaries.
- **Frontend:**
  - Server data with TanStack Query.
  - Errors through `apiErrorMessage`.
  - Every view handles loading, error and empty states.
  - Role-gated parts use `RequireRole`.
  - Forms use React Hook Form + Zod for new work (see `CustomerDetailPage`); older forms use
    `useState`.
  - No Redux.

---

## 6. Build, run, test

There is **no Maven wrapper**: use `mvn`.

```bash
# Backend
cd cmo2-backend
mvn test                 # ~136 tests, H2 + in-memory Quartz, no MySQL or keys needed
mvn spring-boot:run      # needs MySQL and application.properties (see below)

# Frontend
cd cmo2-frontend
npm install
npm run build            # tsc --noEmit + vite build — use this as the frontend check
npm run dev              # http://localhost:5173
```

- `cmo2-backend/src/main/resources/application.properties` is **git-ignored** (it holds local
  defaults). A fresh clone needs one; every setting can also come from env vars listed in
  `.env.example`.
- No keys needed by default:
  - `CMO2_AI_PROVIDER=stub` (demo AI)
  - GHL demo mode for `demo-…` keys
  - workspace tools simply not connected
- Demo with seeded data: `DEMO_RUNBOOK.md` (Spring profile `demo`; never in production).
- There are **no frontend tests yet**, and eslint isn't installed, so `npm run lint` fails.
- Swagger: http://localhost:8080/swagger-ui.html.

---

## 7. Testing conventions

- **Flow tests:**
  - `@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")`
  - register a fresh agency per test through `/api/auth/register` (unique emails)
  - activate the plan with `/api/billing/simulate-activation`
  - drive the real API
- **Mocking:** mock only the outside world (`NotionClient`, `LinearClient`, `GhlSocialPlanner`,
  AI providers…) with `@MockBean`. HTTP clients are unit-tested with `MockRestServiceServer`; the
  Anthropic SDK against a local `HttpServer`.
- **Async loop:** wait for cycles with Awaitility (`await().atMost(...)`).
- **Required per feature:** a tenant-isolation check (other agency → 404) and a role check
  (lower role → `AccessDeniedException`).
- **Odd failures** (duplicate classes, wrong config): run `mvn clean test`. Stale `target/`
  contents have caused false failures.

---

## 8. Definition of done

1. Endpoint(s) and service logic with Bean Validation on request DTOs.
2. Tenant isolation and role checks covered by tests.
3. `mvn test` green and `npm run build` green.
4. UI with loading / error / empty states.
5. Audit events (`AuditService.record`) for decisions and changes.
6. Mapped to a Strange Loop stage.
7. `docs/PROJECT_STATE.md` updated if it changes what's built, unverified or next.

---

## 9. Gotchas

- **Don't put the repo in iCloud Drive** (Desktop or Documents sync). It created "… 2"
  duplicates, restored deleted files and rolled back source files in the old copy.
- **Constructors:** a Spring bean with two constructors needs `@Autowired` on the one Spring
  should use. The package-private ones exist for tests.
- **Records:** compact canonical constructors validate; keep old arities as extra constructors
  when adding fields (see `AiRequest`, `SyncResult`, `SyncRequest`).
- **Pinned external versions:**
  - Notion API is pinned to `2022-06-28` (database model).
  - GHL `Version` headers are chosen per path in `GhlGateway.versionFor`.
  - Claude defaults: `claude-opus-5-5` (strategy, generation), `claude-haiku-5-5` (translation).
  - OpenAI defaults: `gpt-5`, `gpt-5-mini`.
- **TypeScript 6 in the editor** flags `baseUrl` as deprecated, so it was removed from
  `tsconfig.json`; keep `paths` relative (`./src/*`).
- `gohighlevel-cli/` is a **reference only** (private-use licence): don't copy its code.
- `_retired_backup_2026-10-27/` holds code removed in the GHL-only redesign. Don't import from it.

---

## 10. External services

| Service | Use |
|---|---|
| GoHighLevel | Agency OAuth app (Agency Pro), sub-account figures, Social Planner posts, custom values. Email waits on `docs/spikes/2026-10-28-ghl-email-spike.md`. |
| AI | Anthropic Claude (strategy and planning), OpenAI (drafts and translation), routed by `AiRouter`, overridable per tier (`CMO2_AI_ROUTE_*`). |
| Owner's workspace tools | Notion, Confluence, Linear, Slack: the owner's own accounts, connected in Settings → Workspace tools. |
| Legacy | Stripe (billing for CMO² itself) and SMTP mail (invites, alerts). |
