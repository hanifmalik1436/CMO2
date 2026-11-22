# CMO² — Project State

**As of 30 October 2026.** Read this before changing anything. It says what CMO² is *now*, what
is built, what has never been tested against the real services, and how to run it. It doesn't
depend on any chat history.

`CLAUDE.md` holds the rules and patterns for working in the code; this file holds the project's
state. The original plan (a self-serve SaaS with 15 connectors and public sign-up) is kept as
`deprecated-Claude.md` for history only.

---

## 1. What CMO² is now

CMO² is the **agency owner's private console** between the owner's white-labelled
GoHighLevel (GHL) agency and that agency's customers.

- **Customers never log in to CMO².** They see only their own GHL sub-account. Snapshots are
  loaded there by the owner, and customers connect their social media inside GHL.
- **The owner (and an admin partner) run everything in CMO².** They see every sub-account in
  the agency, enable the ones CMO² should manage, and approve everything it writes.
- For each customer, CMO² runs the **Strange Loop** on a daily, weekly or monthly schedule:
  1. **Plans** the cycle with Claude, from the customer's profile, figures and the last cycle's record.
  2. **Drafts** social posts (written and translated EN→ES by OpenAI, or by Claude if there's no
     OpenAI key) and puts them in the **approval queue**.
  3. **Records** the cycle (decisions, tasks done and pending, content, prompts and responses) in
     its database and in the owner's own Notion/Confluence, and keeps a Linear project in step.
  4. **Learns** from the week's figures and **proposes experiments**, chosen only from a fixed list.
  5. **Starts the next cycle** from that record plus anything the owner changed in Notion,
     Confluence or Linear.

Nothing client-facing leaves CMO² without the owner's approval.

---

## 2. Decisions made (and why)

| Topic | Decision |
|---|---|
| Who uses CMO² | Only the owner (OWNER) and a partner (ADMIN). Sign-up is open to the first account only (`CMO2_SELF_REGISTRATION=first-user-only`); everyone else is invited. |
| Data model | **Agency = tenant. Each customer = a Company**, linked 1:1 to a GHL sub-account (`locationId`, unique across all tenants). |
| GHL access | One **GHL Marketplace app** installed on the agency (Agency Pro plan). CMO² mints a per-sub-account token from the agency token (`/oauth/locationToken`). |
| Sub-accounts | Created in GHL by the owner (with the Snapshot). CMO² only lists and links them. Snapshots can't be applied through the API. |
| Records | Written to the **owner's own** Notion / Confluence / Linear / Slack, one page or project per customer. GHL doesn't expose credentials a customer connects inside GHL. |
| Memory | **CMO²'s database is the source of truth**; Notion/Confluence are a readable mirror. The owner's edits there come back as *notes*, always treated as data, never instructions. |
| Publishing | Every draft starts **PENDING_APPROVAL**. Social posts are always *scheduled* in GHL's Social Planner (never posted instantly), and never retried automatically: an unanswered post is "unconfirmed" and needs a person to confirm before retrying. |
| AI | Strategy → Claude (`claude-opus-5-5`). Drafts and translation → OpenAI (`gpt-5`, `gpt-5-mini`) when its key is set, else Claude. All calls go through `AiService` with agency and per-customer budgets, and every call is recorded (kept 180 days). |
| Experiments | AI may only choose: a social post draft, changing one existing GHL custom value, or a suggestion for the owner. Custom value changes **wait for approval by default**, because they can feed live GHL workflows and emails. |
| `gohighlevel-cli` (in this repo) | **Reference only.** Its licence is "private / personal use"; none of its code is in CMO². |
| Email | **Not built.** Waiting on a spike (§5). |

The full approved plan was in a Claude Code plan file outside this repo; its substance is
captured here.

---

## 3. What is built (Phases 0–7 of the October 2026 plan)

All phases are implemented and covered by tests (136 backend tests, all passing on 29 Oct 2026).
"Built" means code and tests, **not** proven against the real services (see §5).

| Phase | What it delivers | Main code |
|---|---|---|
| 0 | GHL-only cleanup: other connectors, public sign-up and old OAuth removed; GHL webhooks verified with Ed25519 | `integrations/`, `_retired_backup_2026-10-27/` (the removed code) |
| 1 | Agency OAuth, list sub-accounts, **Enable** → customer; `GhlGateway` (retries, rate limits, paging); capability registry with risk tiers | `integrations/gohighlevel/`, `…/capability/` |
| 2 | Owner's Notion/Confluence/Linear/Slack connections (encrypted), a page or project per customer, Slack alerts | `docs/` |
| 3 | Real GHL figures (contacts, pipeline, revenue, appointments, unread conversations, social accounts), daily rollups in the customer's timezone, customer profile, **identity block** sent with every AI call | `integrations/gohighlevel/GhlLocationReader`, `analytics/`, `customers/` |
| 4 | Approval queue (edit / approve / reject), scheduling approved posts in GHL Social Planner, no double posts | `assets/` (`ApprovalService`, `Deployer`), `GhlSocialPlanner` |
| 5 | Per-customer schedules in **Quartz** (MySQL, survive restarts), AI call log, per-customer AI budget, **Boot** (load memory + owner's notes) and **Close** (write the cycle record) | `strangeloop/schedule/`, `strangeloop/memory/`, `ai/` |
| 6 | Claude + OpenAI side by side with per-task routing, structured JSON output (`AiService.generateJson`), Stage 1 plans with AI and drafts posts | `ai/`, `strangeloop/stages/Stage1Innovation` |
| 7 | Stage 2 learning records, Stage 4 gaps, Stage 5 experiments (propose → owner decides → measured in a later cycle) | `strangeloop/learning/`, `strangeloop/experiments/`, stages 2/4/5 |
| + | **Brand canon + cached customer context** (19 Nov 2026, drift-control plan step 1): a versioned canon per customer (staff draft, owner/admin approve, history kept); only the approved version reaches the AI, in full, as the first, cached system block of every call. Weekly figures moved out of the identity block into the cycle plan's request. AI call log and budgets count prompt-cache reads (at a tenth) and writes | `customers/service/CustomerCanonService`, `customers/service/CustomerIdentityService`, `ai/AiMessages`, `ai/provider/` |
| + | **Content Studio** (30 Oct 2026): paste a transcript/notes/article → AI finds ideas (hooks, quote) → pick ideas and formats (15 formats across X, LinkedIn, YouTube, TikTok, Threads, Instagram, Facebook, Google Business, website, Substack) → each format's own agent writes the drafts in the background → all land in Approvals. GHL-supported post formats publish through Social Planner; the rest (threads, carousels, scripts, articles, newsletters) are copy & paste. Max 40 pieces per batch; stops cleanly when the AI budget runs out. With no AI key, demo drafts are built from the real source text | `studio/`, `assets/domain/ContentFormat`, `ai/provider/StubContentWriter` |

**Owner screens (frontend):** Customers, the customer page (`/customers/:id`: schedule, last 7
days, learning & experiments, cycle memory, brand canon, profile, "what the AI is told", AI calls), Approvals,
Content Studio, Strange Loop, Integrations, Settings → Workspace tools.

**Older modules still present** (from the original SaaS plan, not part of the owner's story):
AI Strategists (17 agents), Companies page, Billing (CMO²'s own plan), Analytics forecast /
what-if, Alerts, Reports.

**Database:** Flyway migrations `V1`–`V19` in `cmo2-backend/src/main/resources/db/migration/`
(`V14` is Quartz's own schema; `V17` is the Content Studio; `V19` is the brand canon and
cache-token columns). The next migration is `V20`.

---

## 4. How to run and test it

Requirements: **Java 17, Maven 3.9+, MySQL 8, Node 20+**. Redis and Mailhog are optional locally
(everything was checked working without them). There is **no `./mvnw`** in this repo; use `mvn`.

**Config:** the backend reads `cmo2-backend/src/main/resources/application.properties`, which is
**git-ignored** because it holds local defaults including test secrets. A fresh clone therefore
can't start the backend until that file exists (see §6). Every setting can also come from an
environment variable; `.env.example` lists them with explanations.

```bash
# Backend tests (H2 in memory; no MySQL or keys needed)
cd cmo2-backend && mvn test            # 136 tests

# Frontend type-check and build (there are no frontend tests yet)
cd cmo2-frontend && npm install && npm run build

# Run locally (uses the MySQL database in application.properties / SPRING_DATASOURCE_*)
cd cmo2-backend && mvn spring-boot:run
cd cmo2-frontend && npm run dev        # http://localhost:5173 — the first sign-up becomes OWNER
```

**Demo with ready-made data:** see `DEMO_RUNBOOK.md` (Spring profile `demo`, its own database,
login `owner@demo.cmo2` / `demo1234`). Checked on 29 Oct 2026: backend and frontend start, and all
14 pages (every menu item plus a customer page) load with no console errors or failed API calls.

**Without keys, everything runs offline:** AI uses a demo provider ("Demo …" text), GHL connections
with a `demo-…` key produce simulated figures, and workspace tools are simply not connected.

---

## 5. What is NOT proven yet (do these before relying on it)

Every external call has been tested only against mocked responses.

| Needs | To prove |
|---|---|
| **GHL Marketplace app + a sandbox sub-account** | Agency install and listing sub-accounts; real figures (revenue units, appointment counting, opportunity search); the first real social post (GHL docs disagree on the `Version` header for Social Planner); listing and changing custom values |
| **The email spike** | Whether GHL's public Campaign V2 API can create and schedule emails. Plan and decision rule: `docs/spikes/2026-10-28-ghl-email-spike.md`. No email-sending code until it's answered. |
| **Claude and OpenAI keys** | One live cycle. Check the Claude refusal-fallback setting is accepted (turn off with `CMO2_AI_ANTHROPIC_REFUSAL_FALLBACK=false` if not), and that `gpt-5` / `gpt-5-mini` are valid on your OpenAI account. Run one Content Studio batch for a customer with an approved canon: from the second draft on, the AI calls list should show cached tokens (a context below the model's minimum cacheable length is simply not cached). |
| **Your Notion / Confluence / Linear** | One cycle that writes pages and issues, then a second that reads your edits back. |
| **GHL app scopes** | Enable the list in `.env.example` (includes `users.readonly` and `calendars.readonly`). |

---

## 6. Next steps (in order)

1. **Make a fresh clone runnable:** move the secret defaults out of `application.properties` into
   environment variables, then commit the file (or a committed template), so the repo runs without
   a hand-made config. Rotate the Stripe test keys and the old Notion/Linear keys that were used
   during development (they were never committed, but they were shared in chat).
2. **Live verification** (§5) with the GHL sandbox, AI keys and your workspace tools.
3. **Email:** run the spike, then build the chosen route.
4. **Production basics:** hosting with HTTPS, MySQL backups, error tracking, CI (GitHub Actions
   running `mvn test` and `npm run build`), tests against real MySQL (Testcontainers) instead of H2.
5. **Legal:** data processing agreement, privacy policy and subprocessor list (Anthropic, OpenAI,
   Notion, Linear), since customers' lead data passes through them.
6. **Product gaps:**
   - reports for customers (via GHL)
   - per-customer targets
   - longer windows for measuring experiments
   - GHL webhook handling (currently verified but not acted on)
   - Slack approvals
7. **Small fixes found in the demo check:**
   - a link from Companies to each customer page
   - show the next run in the customer's timezone
   - "UTC" instead of "Z"
   - update the Dashboard's "Getting started" text
   - friendlier Customers page when no GHL app is configured
8. **Install eslint** so `npm run lint` works, then add the first frontend tests.
9. **Plans saved for later** (in `docs/plans/`):
   - `2026-11-19-drift-control-canon-lanes-graph.md`: step 1 done; next are lanes, the drift
     check, the graph view and (later) vector search.
   - `2026-11-04-per-customer-ai-memory.md`: memory block for every AI task, throughput at
     1,000 customers, isolation test, identity drift.
   - `2026-11-12-architecture-review-and-gaps.md`: gaps at 1,000+ customers and a phased plan.

---

## 7. Warnings

- **Don't keep this project in iCloud Drive** (Desktop or Documents with iCloud sync on). The
  original copy lived at `~/Desktop/ETaxLoad_Project/Dream project /1/demo/We Pay Property Taxes`,
  where iCloud repeatedly broke the build:
  - it created "… 2" duplicates of source and build files
  - it restored a file that had been deleted on purpose
  - it rolled `GhlAgencyService.java` back to an old version

  Work only in this folder (`~/Projects/CMO2`) and treat the Desktop copy as stale.
- **Never enable the `demo` Spring profile in production:** it seeds users with a known password.
- **Git:** remote `origin` is `github.com/hanifmalik1436/CMO2`. On 30 Oct 2026 no real keys were
  found in any commit; the only key-like strings are fake tokens inside two test files. Keep
  `application.properties`, `.env` and `_retired_backup_*/` out of commits.

---

## 8. Where things are

| What | Where |
|---|---|
| Backend modules (package-by-feature) | `cmo2-backend/src/main/java/com/cmo2/`: `ai`, `alerts`, `analytics`, `assets`, `auth`, `billing`, `customers`, `docs`, `integrations`, `reports`, `shared`, `strangeloop`, `strategists`, `tenancy` |
| Frontend features | `cmo2-frontend/src/features/` (mirrors the backend modules) |
| All settings, explained | `.env.example` |
| Demo instructions | `DEMO_RUNBOOK.md`, `docker-compose.demo.yml` |
| Email spike | `docs/spikes/2026-10-28-ghl-email-spike.md` |
| Code removed in the GHL-only redesign | `_retired_backup_2026-10-27/` (git-ignored for new files) |
| API docs (when running) | http://localhost:8080/swagger-ui.html |
