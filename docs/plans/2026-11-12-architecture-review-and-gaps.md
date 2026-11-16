# Architecture review: gaps and improvements for 1 agency + 1,000+ sub-accounts

**Status:** Not started. Reviewed 12 Nov 2026. This was a read-only review: no code was changed.

**How to use this file:** give it to Claude together with `docs/PROJECT_STATE.md` and say which
phase to implement (for example "implement Phase A of the architecture review plan"). Each phase
stands on its own. File paths are relative to the repo root; backend paths under `com/cmo2/` are
in `cmo2-backend/src/main/java/`.

**Related plan:** `docs/plans/2026-11-04-per-customer-ai-memory.md` covers the AI memory block,
spreading schedule times, the AI concurrency limit, the customer memory isolation test and identity
drift. This file does not repeat those; it points to them where they fit.

---

## 1. The picture today

```
 Customer (a business)                          Agency owner + partner
        | logs in to                                    | logs in to
        v                                               v
 +---------------------------+              +--------------------------------+
 | GHL sub-account           |              | CMO2 frontend (React 19, Vite)  |
 | (white-labelled domain)   |              | 14 menu pages, owner-only       |
 | contacts, pipeline,       |              +---------------+----------------+
 | Social Planner, calendar  |                REST + JWT    |  WebSocket (loop progress only)
 +-------------^-------------+              +---------------v----------------+
               | GHL API v2                 | CMO2 backend: ONE Spring Boot   |
               | agency OAuth app ->        | app, 15 modules                 |
               | one token per sub-account  |  - Quartz: 1 schedule/customer  |
               +----------------------------+  - Sync sweep: every 15 min     |
                                            |  - Strange Loop: 5 stages       |
   Claude / OpenAI  <-----------------------+  - AiService: budgets + call log|
   Notion / Confluence / Linear / Slack <---+  - docs: a page per customer    |
                                            +---------------+----------------+
                                                            v
                                            MySQL 8, one database
                                            tenant_id = the agency, company_id = a customer
```

| Layer | What exists |
|---|---|
| **Who logs in** | Only the owner and invited team. The customer never sees CMO²; they see its results inside their GHL sub-account (scheduled posts, figures). |
| **Frontend** | One React app. Every page is loaded at start-up. Server data through TanStack Query, the login session in Zustand (saved in the browser's localStorage). |
| **Backend** | One deployable. Stateless JWT security, `@PreAuthorize` on services, one `GhlGateway` for every GHL call, one `AiService` for every AI call. Background work: Quartz (cycle schedules, stored in MySQL), one Spring scheduler thread (sync, alerts, reports, clean-up), a loop thread pool and a studio thread pool. |
| **Database** | MySQL 8 through Flyway `V1`–`V17`. Every table has `tenant_id`; customer tables also have `company_id`. A Hibernate filter adds `WHERE tenant_id = ?` automatically. |
| **Hosting** | Not defined yet. Docker Compose for local use; no CI, no monitoring, no backups. |

## 2. What is already right (keep it)

- **Agency = tenant, customer = Company, one GHL sub-account each** (unique `ghl_location_id`).
- **One door per outside service:** `GhlGateway` (GETs retried, writes never replayed) and `AiService`
  (budget, routing, every call recorded). New features inherit both.
- **Everything client-facing waits for approval**, and social posts are scheduled, never sent twice.
- **Cycles resume after a crash** (`loop_stage_runs`) and schedules survive restarts (Quartz in MySQL).
- **CMO²'s database is the source of truth**; Notion/Confluence are a mirror.
- **A modular monolith on one server is the right shape** for one agency. Nothing below needs
  microservices or a message broker.

The design is sound. The gaps are almost all one thing: **it was built and tested with a handful
of customers, and several parts assume a handful.**

---

## 3. Gaps found

Severity: **A** = breaks or blocks at 1,000 customers · **B** = risk to fix before going live ·
**C** = clean-up.

### 3.1 Product and cross-cutting

| # | Sev | Gap | Evidence |
|---|---|---|---|
| P1 | A | **One person approves everything.** Each cycle drafts 1 post per customer by default, and a Content Studio batch up to 40. With 1,000 customers on a daily schedule that is up to 1,000 new decisions a day in one flat list. | `cmo2.loop.drafts-per-cycle` (default 1); `assets/service/ApprovalService.java:58-75` returns every pending draft, unpaged, with one extra query per row |
| P2 | A | **Leftover SaaS plan limits fight the owner-console model.** A new agency starts on STARTER: 1 customer, 3 connections, 500,000 AI tokens a month. The second "Enable" is refused unless the plan is changed through the billing screen. Even ENTERPRISE has one agency-wide ceiling of 15M tokens a month, which is about 15,000 tokens per customer at 1,000 customers (a few AI calls), while the per-customer ceiling defaults to 2M. The two limits contradict each other. | `tenancy/domain/PlanCode.java`; `tenancy/service/TenantProvisioningService.java:34`; `tenancy/service/CompanyService.java:64`; `integrations/service/ConnectionService.java:73,101`; `ai/CostGuard.java` |
| P3 | A | **The automatic safety net is at agency level, not customer level.** The Hibernate filter only adds `tenant_id`. With one agency it separates nothing between customers. Keeping customer A's data out of customer B's screen or prompt depends on every query including `company_id` and every service remembering `TenantGuard.owned(...)` (25 hand-written call sites). Only one isolation test exists and it tests agency against agency. | `shared/tenancy/BaseTenantEntity.java`; `shared/tenancy/TenantGuard.java`; `src/test/.../tenancy/TenantIsolationTest.java` |
| P4 | B | **Onboarding and offboarding are manual and incomplete.** Enable is one click per sub-account (1,000 clicks); there is no bulk enable. A customer is only ACTIVE or ARCHIVED: no "paused", no service level, no assigned team member, no own AI budget. Nothing happens in CMO² when a sub-account is deleted in GHL or the app is uninstalled. There is no way to export or erase one customer's data. | `integrations/gohighlevel/GhlCustomerService.java`; `tenancy/domain/CompanyStatus.java`; `tenancy/domain/Company.java` |
| P5 | B | **The customer sees nothing of CMO².** That is the current decision, but the service is sold to them. The security headers also forbid showing any CMO² page inside GHL (`X-Frame-Options: DENY`, `frame-ancestors 'none'`). See decision 2 in §4. | `shared/config/SecurityConfig.java` |
| P6 | C | **Old SaaS modules are still in the menu:** Companies (duplicates Customers), Asset Generator, AI Strategists, Billing, Analytics what-if. More screens to keep secure and to explain. | `cmo2-frontend/src/app/AppShell.tsx:17-30` |
| P7 | B | **Claude reads the wrong rules.** The root `CLAUDE.md` is the *deprecated* SaaS plan (its first line says so). The current rules are in `New - CLAUDE.md`, which is not loaded automatically. `PROJECT_STATE.md` refers to a `deprecated-Claude.md` that does not exist. Every AI session therefore starts from the old plan (15 connectors, public sign-up, Stripe). | repo root: `CLAUDE.md`, `New - CLAUDE.md` |

### 3.2 Backend

| # | Sev | Gap | Evidence |
|---|---|---|---|
| B1 | A | **The 15-minute sync runs customers one after another on a single thread.** Each sync makes a few dozen GHL calls. At 1,000 customers one pass takes far longer than 15 minutes, so figures go stale. The same thread also runs alerts, the weekly reports and the AI-log clean-up, so those wait behind it. Each sync also keeps a database transaction open during all its GHL calls. | `integrations/service/SyncScheduler.java:54-77`; `integrations/service/SyncService.java` (`@Transactional syncConnection` wraps `pullData`); no `spring.task.scheduling.pool.size` is set |
| B2 | A | **Cycles can be dropped and then stay blocked.** The loop pool is 8 threads with a queue of 100 and the default "reject" behaviour. The cycle row is saved as RUNNING *before* it is queued. If more than about 108 cycles are waiting, the next is rejected, its row stays RUNNING, and every later scheduled run for that customer is skipped as "previous one still running" until the server restarts. There is also no time-out for a cycle that hangs. (Read from the code; not reproduced in a test.) | `strangeloop/orchestrator/LoopConfig.java`; `LoopOrchestrator.startCycle`; `strangeloop/schedule/LoopScheduleService.java:162-180` |
| B3 | A | **AI calls have no traffic control.** No limit on calls in flight, no wait-and-retry on a provider 429, no prompt caching (the system prompt per format is the same for every customer). The per-customer budget check adds up that customer's month of `ai_interactions` rows before *every* call. Budgets are in tokens, not money, although Claude and OpenAI models cost very different amounts per token. | `ai/AiService.java`; `ai/provider/AnthropicProvider.java`; `ai/repository/AiInteractionRepository.java:21` — first two items are in the memory plan §2 |
| B4 | B | **GHL webhooks are stored but never used, and the door is open.** An event with a bad signature is still saved (only flagged), on a public address that is exempt from the rate limit, so anyone can fill the table. No duplicate check, no clean-up, nothing reacts to "app uninstalled" or "sub-account created/deleted". | `integrations/service/WebhookService.java:44-70`; `shared/ratelimit/RateLimitFilter.java` (`/api/webhooks/` exempt) |
| B5 | B | **Important state lives only in memory.** API rate-limit buckets, GHL per-sub-account limiters, and the lock that protects the agency's single-use refresh token. All reset on restart, and all make a second server unsafe: two servers refreshing the agency token at once would lock CMO² out of GHL. Redis is configured but unused. The API limit is also one shared bucket for the whole agency (240 a minute for every user and screen together), the per-IP buckets are never removed, and `X-Forwarded-For` is trusted without a check. | `shared/ratelimit/RateLimitFilter.java`; `integrations/gohighlevel/GhlGateway.java`; `GhlAgencyService.refreshLocks` |
| B6 | B | **Secrets are encrypted with one key that cannot be rotated.** The stored value does not record which key encrypted it, so changing the master key makes every saved token unreadable. (The old rules promised per-agency data keys; with one agency, key *versions* matter more.) | `shared/crypto/AesGcmService.java` |
| B7 | B | **List endpoints run a query per customer.** Portfolio figures (metrics × customers), the schedules list, the sub-accounts list (also re-reads every sub-account from GHL on each page load) and the approval queue. Fine at 10 customers, seconds-long at 1,000. | `analytics/service/AggregationService.java:54-71`; `LoopScheduleService.list`; `GhlCustomerService.listSubAccounts`; `ApprovalService.queue` |
| B8 | B | **Workspace tools have no pacing.** One Notion/Confluence page and Linear project per customer per cycle, with no rate limiting or 429 handling in the module. Notion allows roughly 3 requests a second. | `com/cmo2/docs/` (no limiter or retry found) |
| B9 | C | **Stale security rule:** `/api/signup/**` is public but no controller exists. Swagger and the API description are public too. | `shared/config/SecurityConfig.java` |
| B10 | B | **Default pool sizes.** 10 database connections serve the web requests, 8 loop threads, 2 studio threads, Quartz and the scheduler. | no `spring.datasource.hikari.*` set |

### 3.3 Database

| # | Sev | Gap | Evidence |
|---|---|---|---|
| D1 | A | **Tables that grow for ever.** Only `ai_interactions` is cleaned up. `kpi_snapshots` gets one row per metric per sync; `sync_jobs` one row per sync (96 a day per customer = 96,000 a day at 1,000 customers); also `webhook_events`, `audit_log`, `loop_stage_runs`, and `reports`, which stores each PDF inside MySQL as text. | `analytics/service/KpiIngestor.java`; `V3__integrations.sql`; `V6__analytics_alerts_reports.sql` (`content_base64`) |
| D2 | B | **Customer-level columns are missing** for the things in P4: status beyond ACTIVE/ARCHIVED, service level, own AI ceiling, assigned user. | `V1__init.sql` (`companies`) |
| D3 | B | **No clean delete of a customer.** Most tables cascade from `companies`, but `loop_iterations` does not, and several tables carry `tenant_id` with no foreign key. | `V2__strangeloop.sql:25` |
| D4 | B | **Tests run on H2, production on MySQL.** Migrations and queries are never run against the real engine before release. | `pom.xml` (h2, test scope); already listed in `PROJECT_STATE.md` §6.4 |
| D5 | C | **JSON is stored as plain long text** (only `companies.brand_settings_json` is a JSON column), so MySQL cannot validate or index it. Acceptable for now; revisit only when something needs to query inside it. | all migrations |
| D6 | B | **No backup or restore procedure**, and this database is the only copy of every customer's memory. | — |

### 3.4 Frontend

| # | Sev | Gap | Evidence |
|---|---|---|---|
| F1 | A | **Every page loads the full customer list into a dropdown.** 12 files do this. No search, no paging, and no shared "current customer": each page asks again. | `cmo2-frontend/src/features/*` (12 files call `/api/companies`); `src/stores/` holds only `session.ts` |
| F2 | A | **Approvals is one flat list** with no grouping by customer, filters or bulk actions (pairs with P1). | `features/assets/ApprovalsPage.tsx` |
| F3 | A | **No fleet view.** The owner cannot see at a glance which of 1,000 customers failed a cycle, lost the GHL connection, ran out of AI budget or have drafts waiting. | `features/dashboard/DashboardPage.tsx` |
| F4 | B | **Five pages poll every 1.5–30 seconds** instead of using the socket, which carries loop progress only. `subscribe()` opens a new socket per call. | `refetchInterval` in 5 files; `src/lib/ws.ts`; `shared/websocket/WsAuthChannelInterceptor.java:54` (one topic allowed) |
| F5 | B | **The refresh token is kept in localStorage**, readable by any script that gets into the page. | `src/stores/session.ts` |
| F6 | C | **All 20 pages load at start-up** (no lazy loading) and there is no error boundary, so one broken panel blanks the whole app. | `src/app/routes.tsx` |
| F7 | C | **No frontend tests, no eslint, almost no shared UI parts** (2 files in `components/ui/`), so each page builds its own tables and forms. | `package.json`; `PROJECT_STATE.md` §6.8 |

### 3.5 Operations

| # | Sev | Gap |
|---|---|---|
| O1 | B | No CI: `.github/` has no workflows. Nothing runs the 142 backend tests or the frontend build on a push. |
| O2 | B | No monitoring. Nobody is told when cycles fail, the queue backs up, GHL's daily quota runs low or AI spend jumps. |
| O3 | C | Seven empty JVM crash-dump files (`cmo2-backend/hs_err_pid*.mdmp`, 0 bytes) are committed. Checked: they hold nothing. Remove them and ignore `hs_err_pid*` and `replay_pid*`. |

---

## 4. Decisions the owner needs to make first

These change what gets built. Recommendations are given, but they are the owner's calls.

1. **Who approves at 1,000 customers?** Today's rule is "nothing leaves without the owner's
   approval". Options, which can be combined:
   - (a) team members each own a group of customers;
   - (b) bulk approve with filters;
   - (c) a per-customer "trusted" setting: drafts that pass the automatic identity check (memory
     plan §4) are approved automatically, the rest wait;
   - (d) the customer approves inside GHL.

   *Recommended:* (a) + (b) now, (c) per customer once the identity check has proved itself,
   (d) only with decision 2. Option (c) changes a core rule, so it needs an explicit yes.
2. **Should the customer see anything of CMO² inside their sub-account?** Options: stay invisible
   (results show up in GHL's own screens), or add one small customer page inside GHL (a GHL custom
   menu link that opens a CMO² page, signed in through GHL's own user context) for approvals and a
   monthly summary. *Recommended:* stay invisible for the first customers; build the page when
   approval volume or customer questions demand it. It needs a separate, minimal frontend entry,
   a new CUSTOMER role limited to one `company_id`, and relaxed frame rules for that one route only.
3. **Service levels per customer.** Do all 1,000 get a daily cycle, or do levels exist (for example
   weekly by default, daily for higher plans)? This sets the AI bill and the server size.
4. **Legacy modules:** hide them from the menu (recommended, reversible) or delete them.
5. **Hosting:** one server plus a managed MySQL is enough for this plan (recommended). A second
   server is not needed for load; it would only be for zero-downtime releases, and B5 must be done
   first.

---

## 5. Plan, in order

### Phase A — Unblock and correct (small; do first)
- **P7:** make the current rules the loaded ones: rename `CLAUDE.md` → `deprecated-Claude.md`, then
  `New - CLAUDE.md` → `CLAUDE.md`. Add the `studio/` module line to the new file.
- **P2:** remove the SaaS caps from the owner console. Simplest: `CompanyService.create` and
  `ConnectionService` stop calling `planLimits` for customers and connections, and the agency AI
  ceiling becomes a setting (`CMO2_AI_AGENCY_MONTHLY_TOKEN_CEILING`, 0 = no limit) instead of
  `PlanCode.monthlyAiTokens`. Keep the per-customer ceiling.
- **B9:** delete the `/api/signup/**` rule; require login for Swagger outside local development.
- **O3:** delete the empty dump files, extend `.gitignore`.
- *Check:* on a fresh in-memory run, enable three customers in a row without a 402; `mvn -o clean test` passes.

### Phase B — Make the customer the unit of isolation
- **P3:** one helper used everywhere a `companyId` arrives from the browser
  (`CustomerScope.require(companyId)` in `shared/tenancy/`), replacing the 25 hand-written checks.
  Add an ArchUnit-style test that fails when a controller method takes `companyId` and its service
  does not go through the helper.
- **Customer leak suite:** for every endpoint with `{companyId}` or an id of a customer-owned row,
  sign in as a user limited to customer A and request customer B's. Extends the test in the memory
  plan §3 from prompts to the whole API.
- **P4 / D2 (Flyway `V18`):** on `companies` add `service_status` (ACTIVE, PAUSED, OFFBOARDED),
  `service_level`, `ai_monthly_token_ceiling` (null = default), `assigned_user_id`. Paused customers
  skip cycles and sync but keep their data.
- **D3:** an "offboard" action that exports one customer's data, then deletes it. Fix the missing
  cascade on `loop_iterations`.
- *Check:* the leak suite passes; pausing a customer stops its next scheduled cycle; offboarding
  leaves no rows with that `company_id`.

### Phase C — Background work that holds at 1,000 customers
- **B1 sync:** replace the one-by-one sweep with a small worker pool (`CMO2_SYNC_THREADS`, default 4)
  that picks the customers whose sync is most overdue. Add a random offset so customers do not all
  fall due together. Do the GHL calls outside the database transaction; open a short transaction
  only to save the result. Give Spring's scheduler its own pool so alerts and clean-up never wait
  behind sync.
- **B1 sync frequency by need:** 15 minutes only for customers with an alert rule or an open screen;
  hourly or before-the-cycle for the rest. This divides the GHL call volume several times over.
- **B2 cycles:** add a QUEUED status. The Quartz job only marks the cycle QUEUED; a dispatcher starts
  queued cycles as threads free up, so nothing is rejected or lost. Add a time-out that fails a
  cycle stuck longer than a set time and frees the customer. Make pool size a setting.
- **B3 AI:** concurrency limit and 429 wait-and-retry (memory plan §2). Add prompt caching for the
  shared system prompts. Keep a running monthly total per customer (one row, updated per call)
  instead of summing the log each time. Record cost in money per call from a small price table, and
  show both.
- **B8:** route Notion, Confluence and Linear calls through one paced client with 429 handling, as
  `GhlGateway` does for GHL.
- **B10:** set the database pool from the thread counts (web + loop + studio + sync + Quartz + spare).
- *Check:* a load test on in-memory H2 with 1,000 demo customers all scheduled for the same minute:
  every cycle finishes, none is skipped or stuck, AI calls in flight never exceed the limit, and a
  full sync pass completes inside its interval.

### Phase D — Data that stays a manageable size
- **D1:** nightly clean-up jobs with settings: `sync_jobs` (30 days), `webhook_events` (30 days),
  `loop_stage_runs.data_json` (keep rows, clear old payloads after 180 days). `kpi_snapshots`: keep
  everything for 14 days, then only the daily rollups (which already exist in `kpi_daily_rollups`).
  Delete in batches so the table is never locked for long.
- **Reports:** store PDFs in file or object storage and keep only the path in MySQL.
- **D4:** run the test suite against real MySQL with Testcontainers in CI.
- **D6:** nightly backup, and one written and rehearsed restore.
- *Check:* after a simulated 90 days for 1,000 customers, table sizes level off instead of growing
  in a straight line.

### Phase E — GHL webhooks become real events
- **B4:** refuse (do not store) events whose signature fails. Store GHL's event id with a unique
  index so repeats are ignored. Process after saving, in the background.
- Handle: app uninstalled → mark the agency connection broken and alert the owner; sub-account
  created → show it as "new, not enabled" (or auto-enable if the owner switches that on);
  sub-account deleted → pause the customer.
- Later: use contact, opportunity and appointment events to refresh one customer's figures on
  change, which removes most scheduled syncs.
- *Check:* a replayed event is processed once; an unsigned event is rejected and leaves no row.

### Phase F — An owner console that works with 1,000 customers
- **API first (B7):** `GET /api/companies` gains `search`, `status`, `page`, `size`. New
  `GET /api/customers/overview`: one row per customer (last cycle result, connection health, drafts
  waiting, AI budget used), built with grouped queries, not a query per customer. Cache the GHL
  sub-account list for a few minutes.
- **F1:** one searchable customer picker in the top bar, kept in a Zustand store and in the URL, used
  by every page in place of the 12 dropdowns.
- **F3:** the Dashboard becomes the fleet view: counts by state, and a table that filters to
  "needs attention".
- **F2 / P1:** Approvals grouped by customer, with filters (customer, format, age, flagged by the
  identity check), keyboard approve/reject, and "approve all shown". Paged.
- **Bulk enable (P4):** tick many sub-accounts, enable them in the background with a progress bar.
- **F4:** add `.activity` and `.alerts` socket topics, share one socket for the whole app, and remove
  the polling.
- **F6, F7, P6:** lazy-load pages, add an error boundary per page, hide legacy menu items, install
  eslint, add first tests for Approvals and the customer picker, and pull the repeated table, form
  and status-badge code into `components/ui/`.
- **F5:** move the refresh token to an HttpOnly, SameSite cookie; keep the access token in memory only.
- *Check:* with 1,000 demo customers, Customers, Dashboard and Approvals each open in under two
  seconds and make a fixed number of requests, whatever the customer count.

### Phase G — Approval at scale and the customer page (after decisions 1 and 2)
- Team ownership: users see and approve only their assigned customers (`assigned_user_id`),
  enforced in the service layer.
- Per-customer trusted mode, if approved: auto-approve only drafts that pass the identity check,
  with an audit entry and a daily digest of what went out.
- Customer page inside GHL, if approved: as described in decision 2.

### Phase H — Production basics
- **O1:** GitHub Actions: `mvn test` on MySQL, `npm run build`, lint.
- **O2:** metrics and alerts for cycle failures, queue length, GHL quota left, AI spend per day,
  sync age. Slack alerts already exist as a channel.
- **B6:** put a key version in front of each encrypted value and support two keys at once, so the
  master key can be changed without downtime.
- **B5:** move rate-limit buckets and the token-refresh lock to Redis or a database lock. Only
  needed before running a second server; fix the shared agency bucket and `X-Forwarded-For` trust
  regardless.

---

## 6. Not doing (named)

- **Microservices, Kafka or a second database.** Nothing found needs them at this size.
- **Switching MySQL to Postgres for vector search.** No current feature needs it (memory plan, "Not
  doing now").
- **Per-customer databases or schemas.** `company_id` plus the Phase B guard and leak suite gives
  the isolation needed at far less cost.
- **Rebuilding the frontend with a UI kit.** Extract shared parts as pages are touched (Phase F).

## 7. Suggested order and size

| Order | Phase | Why here | Rough size |
|---|---|---|---|
| 1 | A | Tiny, and nothing past one customer works without it | hours |
| 2 | B | Isolation must be proven before volume makes a leak expensive | days |
| 3 | C | The parts that actually fail at 1,000 | 1–2 weeks |
| 4 | D | Needed within the first months of real data | days |
| 5 | F | The owner cannot operate 1,000 customers on today's screens | 1–2 weeks |
| 6 | E | Removes load and manual work; not a blocker | days |
| 7 | H | Before real customers' data is in it | days |
| 8 | G | Depends on decisions 1 and 2 | after decisions |

The memory plan (`2026-11-04`) fits alongside Phase C: its §2 is the same work as B3, and its §1,
§3 and §4 can follow Phase B.
