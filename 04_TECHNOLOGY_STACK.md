# CMO² Master Command Platform
## Document 4 of 5 — Technology Stack

**Version:** 1.0 | **Date:** June 2026
**Companion documents:** 01 Overview · 02 Architecture · 03 High-Level Plan · 05 Features

> **Version note:** the request specified "React 20" — that version does not exist. Current stable is **React 19**, which is what this stack uses. Java 17 is the LTS minimum required by Spring Boot 3.

---

## 1. Backend Stack

| Technology | Version | Role | Why this choice (plain English) |
|---|---|---|---|
| **Java** | 17 (LTS) | Language | Long-term support until 2029+; records, sealed classes, and pattern matching make code shorter and safer |
| **Spring Boot** | 3.3.x | Application framework | The industry standard for Java SaaS — security, data access, scheduling, and observability are first-class, not add-ons |
| **Spring Security** | 6.x | AuthN/AuthZ | Battle-tested filter chain; JWT, OAuth2, and method-level RBAC built in |
| **Spring Data JPA (Hibernate)** | 6.x | ORM | Maps Java objects to MySQL tables; parameterized queries make SQL injection nearly impossible; `@TenantId` support powers multi-tenancy |
| **Flyway** | latest | DB migrations | Version-controlled SQL files (`V1__init.sql`…) keep every environment's schema identical and changes reviewable |
| **Quartz** (via Spring) | latest | Job scheduling | Persistent, cluster-safe scheduling for Strange Loop cycles and platform syncs — jobs survive restarts |
| **Bucket4j** | latest | Rate limiting | Token-bucket limits per tenant/IP, backed by Redis, shared across instances |
| **Spring WebSocket (STOMP)** | — | Real-time push | Live loop progress, activity feed, and alert toasts without polling |
| **springdoc-openapi** | latest | API docs | Auto-generated, always-current Swagger UI for every endpoint |
| **MapStruct** | latest | DTO mapping | Compile-time entity↔DTO mapping — no reflection overhead, no hand-written boilerplate |
| **Lombok** | latest | Boilerplate reduction | Generates getters/builders/constructors; keeps entities readable |
| **Maven** | 3.9+ | Build tool | Predictable, universally understood Java builds |

---

## 2. Frontend Stack

| Technology | Version | Role | Why this choice |
|---|---|---|---|
| **React** | 19 | UI library | Latest stable; improved hooks (`use`, Actions), better Suspense — the safe modern default |
| **TypeScript** | 5.x | Language | Types catch a whole class of bugs before runtime; essential at 11-module scale |
| **Vite** | 5.x | Build tool / dev server | Instant hot reload; the successor to deprecated Create React App |
| **Tailwind CSS** | 3.x | Styling | Utility classes recreate the prototype's glassmorphism design system quickly and consistently |
| **shadcn/ui** | latest | Component library | Accessible, themeable primitives (dialogs, dropdowns, tables) you own as source code — no vendor lock-in |
| **TanStack Query** | 5.x | Server-state management | Caching, background refetch, optimistic updates — eliminates hand-written loading/error plumbing |
| **Zustand** | 4.x | Client-state management | Tiny global store for session + selected company; far simpler than Redux for this scale |
| **React Router** | 6.x | Routing | Route-per-module with nested layouts and guards |
| **React Hook Form + Zod** | latest | Forms + validation | Performant forms; Zod schemas mirror backend validation rules |
| **Recharts** | 2.x | Charts | React-native charting for KPI panels; replaces the prototype's Chart.js |
| **Axios** | 1.x | HTTP client | Interceptors handle auth headers and silent token refresh in one place |
| **STOMP.js + SockJS** | latest | WebSocket client | Pairs with Spring's STOMP broker; auto-reconnect with fallbacks |

---

## 3. Data & Infrastructure

| Technology | Version | Role | Why |
|---|---|---|---|
| **MySQL** | 8.0+ | Primary database | Chosen per requirement; JSON columns + window functions cover the hybrid relational/flexible needs; ubiquitous managed hosting |
| **Redis** | 7.x | Cache / counters / queue | Session-adjacent caching, rate-limit buckets, lightweight job coordination |
| **Docker + Docker Compose** | latest | Containers | One-command identical environments for every developer and for production |
| **Nginx** (or Caddy) | latest | Reverse proxy | TLS termination, static asset serving, compression |
| **Testcontainers** | latest | Integration testing | Spins up a *real* MySQL in CI so tests prove behavior against the actual database engine |

---

## 4. External Services & APIs

| Service | Purpose | Notes |
|---|---|---|
| **Anthropic Claude API** | Strategy generation, strategist agents, optimization suggestions | Frontier-model tier for high-stakes reasoning |
| **OpenAI API** | Bulk content + translation tier (optional second provider) | Provider-agnostic `AiService` allows per-task model routing |
| **Stripe** | Subscriptions, checkout, customer portal, webhooks | Stripe-hosted pages = no card data touches the server (minimal PCI scope) |
| **SendGrid / Amazon SES** | Transactional + alert email | Verification emails, password resets, KPI alerts, reports |
| **Twilio** | SMS alerts | KPI breach notifications channel |
| **Slack Incoming Webhooks** | Team alert channel | Zero-OAuth simple delivery for v1 |
| **GoHighLevel API** | CRM/automation connector #1 (reference implementation) | Contacts, campaigns, pipelines, KPIs, webhooks |
| **Meta Graph API, LinkedIn, Google Ads, TikTok APIs** | Ads & social connectors | Phase 8 rollout order per commercial priority |
| **ReachInbox, Nureply, Sendr, Leadpro, ManyChat, SlyBroadcast** | Outreach/chat connectors | Same `PlatformConnector` pattern |
| **Notion / Confluence / Linear APIs** | Strange Loop Stage-3 auto-documentation | Insights, SOPs, and gap reports pushed automatically |

---

## 5. Security Tooling

| Tool / mechanism | Purpose |
|---|---|
| BCrypt (cost 12) | Password hashing |
| JJWT / Spring OAuth2 Resource Server | JWT issuing + validation |
| TOTP (e.g., dev.samstevens.totp) | MFA via authenticator apps |
| AES-256-GCM (JCA) + envelope keys | Encryption of stored platform API keys |
| OWASP Dependency-Check + Dependabot | Vulnerable-dependency gates in CI |
| Gitleaks / secret scanning | Prevent committed credentials |
| HMAC-SHA256 verification | Inbound webhook authenticity (Stripe + platforms) |

---

## 6. DevOps, Quality & Observability

| Tool | Role |
|---|---|
| **GitHub Actions** | CI/CD: build → tests → leak suite → scans → image → staging → gated prod |
| **JUnit 5 + Mockito + Testcontainers** | Backend unit + integration testing |
| **Vitest + React Testing Library + Playwright** | Frontend unit/component + E2E smoke tests |
| **Spring Boot Actuator + Micrometer** | Health checks and metrics export |
| **Prometheus + Grafana** | Metrics storage + dashboards (latency, error rate, sync health, AI spend) |
| **Sentry** | Frontend + backend error tracking with release tagging |
| **MySQL nightly dumps + binlog** | Backups with periodically *tested* restores |

---

## 7. Hosting Path

| Stage | Setup | Approx. cost |
|---|---|---|
| Development | Docker Compose on laptops | $0 |
| Launch (v1) | Single VPS / AWS Lightsail: Nginx + app container + MySQL + Redis | $20–60/mo |
| Growth | AWS ECS (or similar) + RDS MySQL + ElastiCache + ALB | scales with revenue |

---

## 8. Explicit Non-Choices (and Why)

| Rejected | Reason |
|---|---|
| React "20" | Does not exist; React 19 is current stable |
| Microservices at v1 | Operational overhead unjustified pre-revenue; modular monolith preserves the option |
| Create React App | Deprecated; Vite is the maintained successor |
| Redux | Overkill for this state footprint; TanStack Query + Zustand cover it |
| MongoDB | Data is strongly relational (tenants→companies→assets); MySQL 8 JSON columns handle the flexible parts |
| Storing card data | Stripe-hosted Checkout/Portal keeps PCI burden near zero |
| Node.js backend (from earlier docs) | Superseded by the Java 17 / Spring Boot requirement; all prior Node sketches are translated, not lost |
