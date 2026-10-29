# CMO² Master Command Platform
## Document 5 of 5 — Features Catalog

**Version:** 1.0 | **Date:** June 2026
**Companion documents:** 01 Overview · 02 Architecture · 03 High-Level Plan · 04 Technology Stack

This catalog enumerates every feature by module, mapped to the build phase that delivers it (see Doc 03). Modules mirror the prototype's navigation: Unified Dashboard, Master Control, Intelligence Hub, Multi-Company, Asset Generator, AI Strategist, Strange Loop OS, Analytics, Reports, Integrations, Settings — plus the cross-cutting SaaS layer.

---

## 1. Authentication & Account (Phase 0)

- Email/password registration with email verification
- Login with JWT access + rotating refresh tokens
- OAuth2 social login (Google, Microsoft)
- TOTP multi-factor authentication (mandatory for Owner/Admin)
- Secure password reset (single-use expiring links)
- Account lockout after repeated failures; session/device management
- Profile management (name, avatar, timezone, notification prefs)

## 2. Multi-Company Manager (Phase 1)

- Create/edit/archive unlimited companies (plan-limited)
- **5-step onboarding wizard:** Company Info → File Upload → API Setup → AI Strategist assignment → Complete (resumable; survives refresh)
- Company switcher in the topbar — all modules re-scope instantly
- Per-company brand settings (industry, voice, palette, naming conventions)
- Company document upload (PDFs/docs) parsed into AI context
- Per-company UTM template generation
- Company-level data isolation within the tenant

## 3. Unified Dashboard (Phases 1, 3, 6)

- Live platform cards for all connected platforms (status, key stats)
- Traffic-light health indicators (Green / Yellow / Red) per connection
- Portfolio KPI strip: Leads, CPL, ROAS, Conversion Rate, Appointments
- Real-time activity feed (WebSocket) of every system + user action
- Quick actions: Quick Deploy, Connect Platform, Run Loop Cycle
- Live charts (Recharts) replacing the prototype's dummy Chart.js data
- Notification center with unread badge

## 4. Integrations Hub (Phases 3, 8)

- **15 platform connectors:** GoHighLevel, ReachInbox, Nureply, Sendr, Leadpro, ManyChat, SlyBroadcast, LinkedIn, Facebook, Instagram, Google Ads, TikTok, Stripe (data), PayPal — built on one `PlatformConnector` pattern
- Connect flow: credentials form → encrypted storage (AES-256-GCM) → live connection test
- Credentials masked after save (`••••last4`); rotate/reconnect flows
- Scheduled background syncs (contacts, campaigns, KPIs) — zero-click refresh
- Signed inbound webhooks (HMAC-verified, replayable event log)
- Per-connection sync logs and error details ("View Logs" made real)
- Automation Health Panel: API success/failure tracking, sync-failure detection, bottleneck flags

## 5. AI Asset Generator (Phase 4)

- **Seven asset types:** Email Sequences, eBooks, Blog Posts, Social Media, Landing Pages, SMS Campaigns, Chatbot Scripts
- Company-context-aware generation (brand voice, industry, uploaded docs)
- **Bilingual pipeline:** generate in English → auto-translate to Spanish → both versions stored
- Template library per asset type with variable substitution
- Asset library with **versioning** (every regeneration is a new linked version)
- In-app preview and editing before deployment
- One-click deployment to selected platforms (e.g., email sequence → GoHighLevel)
- Bulk generation (e.g., full welcome pack on company creation — auto-triggered)
- Per-tenant AI token metering visible in the UI

## 6. AI Strategist Council (Phase 5)

- **17 specialist agents in 4 phases:**
  - *Phase 1 — Insights & Brand (5):* Market Analysis, Competitor, Brand Analysis, Brand, Pricing
  - *Phase 2 — Distribution (2):* Distribution, Partnership & Ecosystem
  - *Phase 3 — Big Media & Content (8):* SEO, Paid Search, Google & Meta Ads, Influencer, Social Media, Email Marketing, Content, Video
  - *Phase 4 — Sales & Operations (4):* Sales, CRM, Conversion Rate Optimization, Analytics & Data
- Master Strategist mode (all-inclusive orchestration of the 17)
- Defined deliverables per agent (e.g., Market Analysis → TAM/SAM/SOM snapshot, segment heatmap, JTBD bullets, audience hypotheses, top-10 keywords)
- Streaming chat interface with full conversation history per session
- **Review Gates RG-1 → RG-4:** human approval checkpoints between phases, role-restricted
- Company Avatar: upload custom persona files that shape agent behavior
- Editable agent system prompts stored in DB (tune without redeploying)
- Approved deliverables flow automatically into Strange Loop Stage 1

## 7. Strange Loop OS (Phases 2, 5, 6, 8)

- **Five-stage autonomous cycle:**
  1. **Innovation** — AI strategy generation (fed by Strategist outputs)
  2. **Learning** — pattern analysis over live KPI snapshots
  3. **Documentation** — auto-push insights/SOPs to Notion, Confluence, Linear
  4. **Reporting** — gap identification vs. targets
  5. **Optimization** — apply fixes, queue suggestions, fire alerts → loop
- Live stage visualization with real progress bars (WebSocket-driven)
- Manual "Run Cycle" + scheduled cycles (Quartz) + event triggers
- Crash-resume: interrupted cycles continue, not restart
- Full iteration history: insights, gaps, and optimizations per cycle
- Configurable loop triggers (conditions → actions)

## 8. Analytics Module (Phase 6)

- KPI dashboards: Leads, CPL, ROAS, CTR, Conversion Rates, Appointments
- Portfolio view + per-company deep dives; cross-brand benchmarking
- Time-series trends with period comparison (WoW / MoM)
- A/B testing panels for creatives and funnels
- Automation performance insights (which workflows produce results)
- Drill-down from any KPI to its source platform data

## 9. Reports Module (Phase 6)

- Scheduled weekly/monthly leadership-ready PDF reports (auto-generated)
- On-demand report builder (select companies, metrics, period)
- Branded report theming (CMO² visual identity)
- Report archive with download history
- Email delivery of scheduled reports

## 10. Alerts & Notifications (Phase 6 → 8)

- Rule engine: per-metric thresholds (e.g., CPL > $X for Y hours)
- Channels: Email (SendGrid/SES), SMS (Twilio), Slack webhook, in-app
- Trigger classes: API/sync failures, KPI breaches, funnel breakdowns, loop anomalies
- Alert center with severity, acknowledge/resolve workflow, audit trail
- **Predictive alerts (Phase 8):** forecasted CPL spikes, ROAS dips, lead-velocity drops, automation instability — warned *before* impact

## 11. Intelligence Hub (Phase 8)

- **AI Optimization Suggestions** queue with one-click apply (e.g., "Shift 20% budget LinkedIn → Instagram", "Lower Meta bid 10% to stabilize CPL")
- **What-If Simulator:** adjust budgets/creatives/channel mix → forecast CPL, ROAS, CTR, lead velocity → interactive scenario graphs → leadership PDF export
- Anomaly detection over KPI streams
- Foundations for the conversational **AI Co-Pilot** (v3): hands-free insights and execution

## 12. Master Control (Phases 3–6)

- One-click campaign launch across selected platforms
- Quick Deploy of approved assets to multiple destinations
- "Connect All Platforms" guided batch flow
- Cross-platform action triggers (the prototype's alert-only buttons, made real)

## 13. Settings & Administration (Phases 1, 7)

- **Team management:** email invitations, five-role RBAC (Owner, Admin, Strategist, Analyst, Viewer), deactivation
- Tenant profile, branding, locale and timezone
- Notification preferences per user and per channel
- API key & webhook management UI
- Audit log viewer (Owner/Admin)
- Tenant data export (GDPR groundwork)

## 14. SaaS Commercial Layer (Phase 7)

- **Three plans** (Starter $97 / Pro $297 / Enterprise $997) with enforced limits: companies, platform connections, seats, monthly AI tokens
- 14-day free trial with automatic conversion flow
- Stripe Checkout signup; Stripe Customer Portal for card/plan self-service
- Webhook-driven subscription lifecycle: active → past_due (grace) → suspended → canceled
- Usage metering dashboard (AI tokens, assets generated, syncs)
- Friendly in-app upgrade prompts at limit boundaries
- Enterprise AI-token overage billing

## 15. Platform-Wide / Non-Functional Features

- **Multi-tenant isolation** enforced at the ORM layer + CI leak-test suite
- Real-time everything: WebSocket loop progress, activity feed, alert toasts
- Immutable audit log (who/what/when/before/after) across all modules
- Rate limiting per tenant and IP; AI CostGuard circuit breakers
- Responsive UI preserving the prototype's glassmorphism design system
- OpenAPI (Swagger) documentation for every endpoint
- Observability: metrics dashboards, error tracking, uptime probes
- Nightly backups with tested restore procedure

---

## Feature → Phase Traceability Matrix

| Module | P0 | P1 | P2 | P3 | P4 | P5 | P6 | P7 | P8 |
|---|---|---|---|---|---|---|---|---|---|
| Auth & Account | ● | | | | | | | ○ | |
| Multi-Company | | ● | | | | | | | |
| Unified Dashboard | ○ | ○ | | ● | | | ○ | | |
| Integrations Hub | | | | ● | | | | | ● |
| Asset Generator | | | | | ● | | | | |
| AI Strategists | | | | | | ● | | | |
| Strange Loop OS | | | ● | | ○ | ○ | ○ | | ○ |
| Analytics | | | | ○ | | | ● | | |
| Reports | | | | | | | ● | | |
| Alerts | | | | ○ | | | ● | | ● |
| Intelligence Hub | | | | | | | | | ● |
| Master Control | | | | ○ | ○ | | ○ | | |
| Settings/Admin | | ○ | | | | | | ● | |
| SaaS/Billing | | | | | | | | ● | |

● = primary delivery ○ = incremental additions
