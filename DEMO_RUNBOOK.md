# CMO² Demo Runbook

How to run CMO² with a ready-made demo agency and walk someone through it in about 10 minutes.
**No API keys or outside accounts are needed.** AI, GoHighLevel and billing run in offline demo
modes, and every figure is simulated.

CMO² is an **owner's console**: you (the agency owner) use it to run marketing for the customers
in your GoHighLevel (GHL) agency. Customers never log in to CMO². The demo shows that console.

---

## 1. What the demo contains

With the `demo` Spring profile on, the backend seeds a **"Demo Agency"** on startup (once; it skips
seeding if `owner@demo.cmo2` already exists):

- **5 users**, one per role (OWNER, ADMIN, STRATEGIST, ANALYST, VIEWER), all with password **`demo1234`**
- **3 customers**: Northwind Solar, Bright Dental and Apex Fitness, each with a **simulated GoHighLevel
  connection** that has already synced, so there are figures and charts
- **Drafts waiting in Approvals**: a 7-email welcome sequence for Northwind, in English and Spanish
- **A Strange Loop cycle for Northwind**: its plan, a social post draft, a learning record, an
  experiment proposal, and the cycle's record (kept in CMO², because no Notion/Confluence is connected)
- **Alert rules and one fired alert**, **two PDF reports**, and a **strategist session** with
  review gate RG-1 approved

The figures cover a single day, so week-over-week comparisons show "nothing to compare with yet".
With the demo AI, AI-written text reads like "Demo objective". §6 shows how to use real AI instead.

---

## 2. Sign in

| Email | Role | Password |
|---|---|---|
| `owner@demo.cmo2` | OWNER (use this one) | `demo1234` |
| `admin@demo.cmo2` | ADMIN | `demo1234` |
| `strategist@demo.cmo2` | STRATEGIST | `demo1234` |
| `analyst@demo.cmo2` | ANALYST | `demo1234` |
| `viewer@demo.cmo2` | VIEWER (to show read-only access) | `demo1234` |

Sign-up is closed in the demo: these accounts are all there is.

---

## 3. Start the demo

Move the project out of the iCloud-synced Desktop first (e.g. to `~/Projects`). iCloud creates
"… 2" copies of build files, which break the build (see §8).

### Option A: run natively (works with what's on your Mac today)

You need Java 17, Maven, MySQL 8 and Node. Use a **separate database** so the demo users never mix
with your real data:

```bash
# 1. A database just for the demo
/usr/local/mysql/bin/mysql -uroot -p -e "CREATE DATABASE cmo2_demo CHARACTER SET utf8mb4;"

# 2. Redis, if you have it (the backend uses it for rate limiting)
brew services start redis

# 3. Backend with the demo profile (there's no ./mvnw in this project; use mvn)
cd cmo2-backend
export SPRING_PROFILES_ACTIVE=demo
export SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/cmo2_demo?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME=root
export SPRING_DATASOURCE_PASSWORD='your MySQL password'
mvn clean spring-boot:run -Dspring-boot.run.main-class=com.cmo2.Cmo2Application

# 4. Frontend, in a second terminal
cd cmo2-frontend
npm install
npm run dev
```

On first start, Flyway creates all tables (16 migrations, including Quartz's schedule tables).
Wait for this banner in the backend log:

```
============================================================
  CMO² DEMO DATA READY  ·  tenant "Demo Agency" (PRO, active)
  Sign in at http://localhost:5173  ·  password: demo1234
  ...
============================================================
```

Mailhog isn't needed natively: the demo users are already verified, and emails (like invites)
fail and are logged instead of sent.

### Option B: Docker (one command)

Needs **Docker Desktop**, which isn't installed on this Mac yet, with Docker Compose v2.24 or later.

```bash
docker compose -f docker-compose.yml -f docker-compose.demo.yml up --build
```

This starts MySQL, Redis, Mailhog, the backend (demo profile) and the frontend. The demo keeps its
data in its own MySQL volume and doesn't use ports 3306/6379 on your Mac, so it can run next to a
MySQL or Redis you already have. Wait for the same banner.

### Where things are

| What | URL |
|---|---|
| The app | http://localhost:5173 |
| API docs (Swagger) | http://localhost:8080/swagger-ui.html |
| Mailhog (Docker only) | http://localhost:8025 |

---

## 4. The demo script (about 10 minutes)

Sign in as **`owner@demo.cmo2` / `demo1234`**.

1. **Dashboard.** Figures across the three customers, refreshed by a background sync every minute
   in demo mode (every 15 minutes for a live GHL sub-account).

2. **Customers.** Explain the model: in real use this page lists every sub-account in your GHL
   agency, and you click **Enable** to make one a CMO² customer. The demo has no GHL Marketplace app,
   so the page says the app isn't configured. That's expected.

3. **A customer's page.** Open **http://localhost:5173/customers/1** (Northwind Solar; Bright Dental
   is 2 and Apex Fitness 3 on a fresh demo database). Nothing links here yet, so type the address.
   - **Strange Loop schedule:** pick *Weekly, Monday, 09:00* and save. The next run appears and survives
     a restart (Quartz, stored in MySQL). **Run a cycle now** starts one immediately.
   - **Last 7 days:** this customer's figures.
   - **Learning & experiments:** what the last cycle read in the figures, and the experiment it proposed.
     Click **Mark done** on a suggestion. Nothing reaches a customer's audience without your approval.
   - **Cycle memory:** the record each cycle writes (decisions, tasks, content, your notes) and the next
     cycle starts from.
   - **Profile:** set the timezone to *Europe/London*. The schedule moves with it.
   - **What the AI is told:** the identity block sent with every AI call for this customer.
   - **AI calls:** every prompt and response, with tokens.

4. **Approvals.** Every draft waits here.
   - Edit the social post's text, then **Save & approve**.
   - In real use, **Schedule in GoHighLevel** opens a picker for the customer's social accounts and a time.
     The demo connection accepts it straight away.
   - **Reject** the Spanish version with a reason. The reason stays with the draft (and with its
     experiment, if it has one).

5. **Strange Loop.** Run a cycle for a customer and watch the five stages complete live (plan → learn →
   record → report → experiments). Go back to the customer's page: there's a new record.

6. **Integrations.** Each customer's GoHighLevel connection: health, sync history, **Sync now**.

7. **Settings → Workspace tools.** Where you connect your own Notion, Confluence, Linear and Slack.
   Optional: connect your own Notion, run a cycle, and show the page it writes.

**Optional extras:** Analytics (forecast and the what-if simulator), Alerts (**Simulate spike**,
then acknowledge), Reports (download a PDF), signing in as VIEWER to show read-only access.
Leave out **Billing** (CMO²'s own plan, not part of the owner's story) and **AI Strategists** (an older
module).

---

## 5. What runs offline, and what it would take to go live

| Part | In the demo | Live |
|---|---|---|
| AI | Demo provider ("Demo …" text) | `CMO2_AI_PROVIDER=anthropic` + `CMO2_AI_API_KEY`; add `CMO2_AI_OPENAI_API_KEY` for drafts and translation |
| GoHighLevel | Simulated figures; publishing is simulated | Not part of the demo: real sub-accounts need your GHL Marketplace app (see `.env.example`) |
| Notion, Confluence, Linear, Slack | Not connected; records stay in CMO² | Settings → Workspace tools (writes real pages and issues) |
| Email | Mailhog (Docker) or logged only (native) | SMTP settings |
| Schedules | Quartz, stored in MySQL | Same |

---

## 6. Optional: real AI text in the demo

The plans, drafts and learning look far more convincing with real AI. Each cycle makes about three
calls per customer, so this costs money.

- **Native:** before `mvn spring-boot:run`, also run
  `export CMO2_AI_PROVIDER=anthropic` and `export CMO2_AI_API_KEY=...`
  (optionally `export CMO2_AI_OPENAI_API_KEY=...`).
- **Docker:** put the same variables in your shell or in a `.env` file next to `docker-compose.yml`.

With only a Claude key, everything runs on Claude. With both keys, Claude plans and OpenAI writes and
translates the drafts. The seeded data was written with the demo AI, so run a new cycle to see real text.

---

## 7. Reset

The seeder runs once per database. To start again with fresh demo data:

```bash
# Native
/usr/local/mysql/bin/mysql -uroot -p -e "DROP DATABASE cmo2_demo; CREATE DATABASE cmo2_demo CHARACTER SET utf8mb4;"

# Docker (-v deletes the demo database volume)
docker compose -f docker-compose.yml -f docker-compose.demo.yml down -v
docker compose -f docker-compose.yml -f docker-compose.demo.yml up --build
```

---

## 8. Troubleshooting

- **Empty app, no demo users:** the `demo` profile isn't on. The backend log should list `demo` among
  the active profiles and show the **DEMO DATA READY** banner.
- **`Unable to find a single main class`** or **`more than one migration with version`:** iCloud has
  copied files as "… 2". Run `mvn clean`, keep the `-Dspring-boot.run.main-class` flag, and move the
  project out of the iCloud-synced Desktop.
- **`./mvnw: no such file`:** this project has no Maven wrapper; use `mvn`.
- **Redis connection errors in the backend log (native):** install and start Redis
  (`brew install redis`, then `brew services start redis`).
- **Docker says `!reset` or `!override` is unknown:** update Docker Desktop (Compose v2.24 or later).
- **Login fails:** the password is `demo1234`, and seeding must have finished (watch for the banner).
- **`Demo seed: … failed` warnings:** not fatal; only that part of the demo data is missing.
- **The customer page shows "Company not found":** on a database that wasn't fresh, the customer ids
  aren't 1–3. Reset (§7), or pick the customer in the Approvals filter: the address bar then shows its
  `companyId`.
- **Never turn on the `demo` profile in production:** it creates users with a known password.
