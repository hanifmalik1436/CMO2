# CMO² Master Command Platform
## Document 3 of 5 — High-Level Plan

**Version:** 1.0 | **Date:** June 2026
**Companion documents:** 01 Overview · 02 Architecture · 04 Technology Stack · 05 Features

---

## 1. Guiding Principles

Adopted directly from the project's Optimal Build Order doctrine — asked before **every** feature:

1. **Can this be automated?** If yes, automate it.
2. **Does this require manual input?** If yes, redesign it.
3. **Which Strange Loop stage does this belong to?** (1–5)
4. **Can an API replace this?** APIs beat file uploads.
5. **Will this self-document?** (Notion / Confluence / Linear)

Plus two engineering rules added for this build:

6. **No phase starts until the previous phase's exit criteria pass.** Exit criteria are tests, not opinions.
7. **Security and tenancy are built first, not bolted on.**

---

## 2. Timeline at a Glance (20 Weeks to Sellable v1)

```
Wk 1  2  3  4  5  6  7  8  9  10 11 12 13 14 15 16 17 18 19 20+
[P0 Found][P1 Tenancy][P2 Loop][P3 GHL ][P4 AI Assets][P5 Strategists][P6 Analytics][P7 SaaS ][P8 Expand→
```

| Phase | Weeks | Theme | Headline deliverable |
|---|---|---|---|
| 0 | 1–2 | Foundation | Auth + app shells running |
| 1 | 3–4 | Multi-tenancy & companies | Proven tenant isolation |
| 2 | 5–6 | Strange Loop core | Live 5-stage cycle |
| 3 | 7–8 | First integration (GoHighLevel) | Real data on dashboard |
| 4 | 9–11 | AI asset generation | Generate → translate → deploy |
| 5 | 12–14 | AI Strategists | 17 agents with review gates |
| 6 | 15–16 | Analytics, alerts, auto-docs | Anomaly → alert in 5 min |
| 7 | 17–18 | SaaS hardening & billing | Stranger can sign up & pay |
| 8 | 19–20+ | Platform expansion & advanced AI | Connectors 2–15, predictive AI |

Reference: the original audit estimated 6–12 months for one developer. This plan is the aggressive-but-honest version of that estimate; with a 2–3 person team, phases 4–6 can run partially in parallel, compressing toward 14–16 weeks.

---

## 3. Phase Detail

### PHASE 0 — Foundation (Weeks 1–2)
**Build:**
- Git repos, Docker Compose stack (backend, frontend, MySQL, Redis, Mailhog)
- Spring Boot 3 skeleton: health endpoint, global error handler, OpenAPI docs
- Flyway V1: `tenants, users, roles, user_roles, companies`
- Complete auth: register + email verification, login, JWT + rotating refresh, password reset, account lockout
- React shell: Vite project, Tailwind + shadcn, AppShell rebuilt from the HTML prototype (sidebar, topbar, breadcrumb), login/register pages, route guards

**Exit criteria:** Two users in two different tenants log in and see empty, isolated dashboards. CI pipeline green on every push.

---

### PHASE 1 — Multi-Tenancy + Multi-Company (Weeks 3–4)
**Build:**
- `TenantContext` + servlet filter + Hibernate tenant filter
- **Tenant-leak test suite** (CI-blocking)
- Multi-Company module: company CRUD, 5-step onboarding wizard (Company Info → File Upload → API Setup → AI Strategist → Complete) persisted as a state machine, company switcher in topbar
- Audit log interceptor; activity feed end-to-end (domain event → WebSocket → live UI widget)
- RBAC: five roles, method security, role-aware navigation

**Exit criteria:** Switching companies switches all data. Leak suite passes on every endpoint. Onboarding wizard survives refresh mid-flow.

---

### PHASE 2 — Strange Loop Core (Weeks 5–6)
**Build:**
- Tables: `loop_iterations`, `loop_stage_runs`, `loop_triggers`
- Five stage services (initially stub logic with real persistence + events)
- `LoopOrchestrator` with Quartz scheduling and crash-resume
- Strange Loop module UI: the 5-stage visualization from the prototype, now driven by live WebSocket progress

**Why before features?** The loop is the operating system; every later feature must answer "which stage do I belong to?" Building it first prevents retrofitting.

**Exit criteria:** Click "Run Cycle" → watch stages 1–5 execute live → iteration stored with insights/gaps JSON → next cycle auto-scheduled.

---

### PHASE 3 — First Platform Integration: GoHighLevel (Weeks 7–8)
**Build:**
- AES-256-GCM encryption service + envelope key wrapping
- `PlatformConnector` interface + GHL reference implementation (test connection, pull contacts/campaigns/KPIs, signed webhook receiver)
- Scheduled sync jobs; `kpi_snapshots` ingestion
- Integrations module UI: connect form, masked credentials, traffic-light health (Red/Yellow/Green), sync logs, reconnect flow
- Unified Dashboard: replace hardcoded platform cards with live data

**Exit criteria:** Real GoHighLevel data appears on the dashboard and refreshes on schedule with zero clicks. Walk away for 30 minutes; data updates itself.

---

### PHASE 4 — AI Asset Generator (Weeks 9–11)
**Build:**
- `AiService` (provider-agnostic) + prompt registry + per-tenant token accounting + `CostGuard`
- End-to-end pipeline for **Email Sequences** first: form → AI generation → auto-translate EN→ES → versioned asset library → deploy to GHL → auto-document → trigger Stage 2 learning
- Fan out the identical pipeline to the remaining six asset types: eBooks, blog posts, social media, landing pages, SMS campaigns, chatbot scripts
- Asset Generator module UI: type sidebar, config forms, EN/ES toggle, template picker, preview, deploy checkboxes

**Exit criteria:** Generate a 7-email sequence, review both language versions, deploy to GoHighLevel from the UI. Token usage visibly metered.

---

### PHASE 5 — AI Strategist Council (Weeks 12–14)
**Build:**
- Seed all **17 agents** across 4 phases (Insights & Brand ×5, Distribution ×2, Big Media & Content ×8, Sales & Ops ×4) with system prompts + deliverable schemas in the DB
- Session engine: context assembly from company data, streaming chat UI, conversation history
- **Review gates RG-1 → RG-4** with approval workflow and role checks
- Company avatar upload + parsing into agent context
- Strategist outputs wired into Strange Loop Stage 1 (Innovation)

**Exit criteria:** Run a Phase-1 Market Analysis agent → receive its deliverables (TAM/SAM/SOM, segment heatmap, JTBD, keywords) → approve RG-1 → deliverables land in the company workspace and the next loop cycle consumes them.

---

### PHASE 6 — Analytics, Alerts & Auto-Documentation (Weeks 15–16)
**Build:**
- KPI aggregation (Leads, CPL, ROAS, CTR, conversion rates, appointments) with portfolio + per-company views; Recharts dashboards; A/B comparison panels
- PDF report generation (scheduled weekly + on-demand, leadership-ready)
- Alert rules engine: threshold conditions per metric → channels: email (SendGrid/SES), SMS (Twilio), Slack webhook; alert center UI with acknowledge flow
- Stage 3 auto-documentation live: insights and SOPs pushed to Notion / Linear / Confluence via their APIs

**Exit criteria:** A simulated CPL spike fires email + Slack alerts within 5 minutes. Weekly report PDF generates and lands in the Reports module automatically.

---

### PHASE 7 — SaaS Hardening & Billing (Weeks 17–18)
**Build:**
- Stripe: products/prices for the 3 plans, Checkout signup flow, Customer Portal, webhook lifecycle (`checkout.session.completed`, `invoice.payment_failed`, `customer.subscription.deleted`) driving tenant status active → past_due → suspended
- `PlanLimitGuard`: companies, connections, seats, AI tokens enforced at service layer with friendly upgrade prompts; 14-day trial flow
- Team management UI: invite by email, role assignment, deactivation
- Security pass: rate limiting live, security headers verified, OWASP Top-10 checklist, dependency scan clean, basic load test, tenant data export endpoint

**Exit criteria:** A stranger signs up, pays, onboards a company, connects a platform, generates and deploys an asset, runs a loop — **with zero assistance**. This is the "sellable" gate.

---

### PHASE 8 — Expansion & Advanced AI (Weeks 19–20+)
**Build:**
- Connectors #2–#15 using the established pattern (~2–3 days each), in commercial priority order: ReachInbox → LinkedIn → Meta (FB/IG) → Google Ads → ManyChat → Nureply → Sendr → Leadpro → SlyBroadcast → TikTok → Stripe(data) → PayPal
- **Predictive alerts** (trend models on `kpi_snapshots`: CPL spike / ROAS dip / lead-velocity forecasts)
- **AI optimization suggestions** queue ("Shift 20% budget LinkedIn → Instagram…") with one-click apply
- **What-If simulator** (scenario inputs → forecast graphs → PDF export)
- Foundations for the conversational **AI Co-Pilot**

**Exit criteria:** Original roadmap phases 10–12 live; phase 13 (Co-Pilot) scoped for v3.

---

## 4. Team & Effort Model

| Setup | Realistic v1 timeline |
|---|---|
| 1 full-stack developer | 20–26 weeks |
| 1 backend + 1 frontend | 14–18 weeks |
| Small team (3–4) | 12–14 weeks |

Recommended minimum: one backend-leaning and one frontend-leaning developer; phases 4–6 parallelize cleanly across that split.

---

## 5. Milestone Demo Schedule (Ship Something Visible Every 2 Weeks)

| End of week | Demo |
|---|---|
| 2 | Login → empty branded dashboard |
| 4 | Add 2 companies, switch between them, live activity feed |
| 6 | Strange Loop cycle running live on screen |
| 8 | Real GoHighLevel KPIs on the dashboard |
| 11 | Generate + deploy an email sequence (EN & ES) |
| 14 | AI Strategist session with gate approval |
| 16 | KPI alert fires to Slack; weekly PDF report |
| 18 | Full self-serve signup with payment — **launchable** |

---

## 6. Risk Register & Mitigations

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| 1 | Cross-tenant data leak | Low (with design) | Critical | ORM-level filter + CI leak suite + audit log |
| 2 | AI cost blowout | Medium | High | Token ceilings, model tiering, CostGuard circuit breaker |
| 3 | GHL/Meta/LinkedIn API changes | Medium | Medium | Connector isolation, contract tests, health monitoring |
| 4 | Scope creep (11 modules) | High | High | Phase exit criteria; nothing starts early |
| 5 | Webhook abuse / forgery | Medium | High | HMAC verification, replay protection, rate limits |
| 6 | Solo-dev burnout | Medium | High | 2-week visible demos; aggressive automation of toil |
| 7 | Stripe lifecycle edge cases | Medium | Medium | Use Stripe-hosted Checkout/Portal; webhook-driven state machine; test clock simulations |

---

## 7. Definition of Done (Applies to Every Feature)

A feature is done only when:
1. Backend endpoint(s) + service logic implemented with validation
2. Tenant isolation verified by the leak suite
3. Unit + integration tests passing in CI
4. Frontend UI wired with loading/error/empty states
5. Audit/activity events emitted
6. Mapped to its Strange Loop stage
7. Documented (auto-doc where applicable)
