> **Action now: save only; no code changes.** The user will implement this later.
> 1. Save the "Per-customer AI memory at 1,000+ customers" plan below (down to its Verification
>    section) as `docs/plans/2026-11-04-per-customer-ai-memory.md`, with a status line at the top:
>    "Not started. Planned 4 Nov 2026."
> 2. Add one line under next steps in `docs/PROJECT_STATE.md` linking to it, so a future session
>    finds it.

# Per-customer AI memory at 1,000+ customers

## Context
The user expects **1 agency (tenant) with 1,000+ customers** (GHL sub-accounts = Companies), all
sharing the same Claude and OpenAI keys. They asked whether each customer's AI conversation history
can be kept separate and loaded back for the daily cycle and for any other AI task.

**Yes, and the core is already built:**
- **The AI providers keep no memory between calls.** Every call is independent, so sharing one API
  key never mixes customers. A customer's "history" is whatever CMO² puts into that call's prompt.
- **`ai_interactions`** (V15) stores every prompt and response with `tenant_id` and `company_id`,
  indexed `(tenant_id, company_id, created_at)` and deleted after 180 days
  (`ai/AiInteractionRecorder.java`). It shows on each customer's page as "AI calls".
- **`cycle_context`** (V15) is one record per customer per cycle: summary, compressed context,
  decisions, and completed and pending tasks.
- **Boot** (`strangeloop/memory/CycleMemoryService.boot(tenantId, companyId, …)`) loads only that
  customer's last record, last 3 summaries, carried-over tasks and the owner's notes.
- **Identity block** (`customers/service/CustomerIdentityService.identityBlock`) goes with every AI
  call: industry, offer, audience, tone, banned topics.

**Gaps found:**
1. Only the Strange Loop uses the memory. The **Content Studio and Asset Generator send just the
   identity block** (`studio/service/ContentBatchRunner.java:96`, `StudioService.java:147`,
   `GenerationService.java:122`), so they don't know what was posted last week or what you
   rejected, and can repeat themselves.
2. **Throughput:** Quartz has 4 threads, the studio pool has 2, and there's no limit on concurrent
   provider calls. 1,000 daily cycles all set to the same hour would queue up and hit Claude/OpenAI
   rate limits (429s).
3. **Isolation between customers** is enforced by `company_id` in each query (the Hibernate filter
   works per tenant/agency). It's correct today, but nothing tests "customer A's memory never
   reaches customer B's prompt".

## Plan

### 1. One customer memory block for every AI task
- New `customers/service/CustomerMemoryService.memoryBlock(tenantId, companyId)` builds a compact
  block, capped at about 1,500 tokens, from:
  - the last cycle summary and compressed context: `CycleContextRepository`, which Boot already uses
  - the latest learning facts: `strangeloop/learning/LearningService.recent`
  - the first lines of the last ~10 approved posts ("don't repeat these openings")
  - the last ~5 rejection reasons ("the owner rejected drafts because…"), from `AssetRepository`
- Every query takes `tenantId` and `companyId` explicitly, matching the background-thread rule.
- `PromptRegistry.forContentPiece`, `forContentIdeas` and `forGeneration` add it as a
  `DATA: what CMO² already knows about this customer` section, framed as data, never instructions.
- Callers pass it in alongside the identity block: `ContentBatchRunner`, `StudioService.analyze`,
  `GenerationService`.
- Build it **once per batch**, not once per piece.

### 2. Run 1,000 customers a day without queueing or 429s
- **Spread the schedules:** when a customer is enabled, the default run time gets a random minute
  between 00:00 and 05:59 in the customer's timezone, instead of everyone at the same time
  (`strangeloop/schedule/`).
- **Configurable thread counts:** `CMO2_QUARTZ_THREADS` (default 4) and `CMO2_STUDIO_THREADS`
  (default 2), so the owner can raise them when moving to a bigger server.
- **Limit concurrent calls per provider:** a semaphore in `AiService` around `provider.generate`,
  set by `cmo2.ai.max-concurrent.anthropic` / `.openai` (default 4 each). A 429 from the provider
  waits for `Retry-After` and retries once. This is safe because an AI call is a read, unlike a GHL
  POST.

### 3. Test that customers never see each other's memory
- `CustomerMemoryIsolationTest`: two customers in the same agency, each with a cycle record, an
  approved post and a rejection. Generating for customer A must send a prompt containing only A's
  text and nothing of B's. Capture the prompt with a `@MockBean` provider, as
  `ContentStudioLiveRoutingTest` does.
- A rejected draft's reason shows up in that customer's next studio prompt.

### Not doing now (named)
- Vector search over all past content (RAG). The compact block covers daily use; revisit if
  customers need years of history.
- Anthropic/OpenAI batch APIs (about 50% cheaper for overnight work): worth doing once real usage
  numbers exist.
- Partitioning `ai_interactions` by month. At about 1,000 customers × 10 calls a day it's tens of
  GB over 180 days; lower `CMO2_AI_INTERACTION_RETENTION_DAYS` if needed.

## Verification
- `mvn -o clean test`: all existing tests plus the new isolation and memory tests pass.
- Demo run on in-memory H2 (`spring-boot:test-run`, profiles `test,demo`):
  1. Approve one studio draft and reject one with a reason.
  2. Generate again.
  3. On the customer's page, "AI calls" shows the new prompt with the memory section: the approved
     opening and the rejection reason.
  4. Another customer's prompt shows none of it.
- Enable 3 customers: each gets a different default run minute.

---

# (Earlier) Going live: Claude + OpenAI keys and the GoHighLevel agency connection

## Context
The user asked: "If I add the Claude, agency and OpenAI API keys, will it start working?"
Answer: **almost.** Keys alone don't switch anything on, because three settings still default to
demo mode, and GoHighLevel connects through an OAuth app rather than a key. Phases 0–7 and the
Content Studio are built and tested (142 tests) but have **never been run against real Claude,
OpenAI or GHL accounts**, so the first live run is also the first real test.

Settings are read from environment variables, or from the git-ignored
`cmo2-backend/src/main/resources/application.properties`; `.env.example` documents each one.
**The user enters the secret values themselves.** Claude never sees, prints or commits them.

## 1. AI: Claude + OpenAI (switches on Content Studio, Strange Loop plans and translations)
| Setting | Value | Why |
|---|---|---|
| `CMO2_AI_PROVIDER` | `anthropic` | **The real switch.** It defaults to `stub`, and while it's `stub` every call stays demo even if keys are set (`ai/AiRouter.java:71`) |
| `CMO2_AI_API_KEY` (or `ANTHROPIC_API_KEY`) | Claude key | Strategy work: finding Content Studio ideas, cycle plans, strategists |
| `CMO2_AI_OPENAI_API_KEY` (or `OPENAI_API_KEY`) | OpenAI key | Writing drafts and translating. Without it, Claude does these too |
| `CMO2_AI_OPENAI_MODEL_*` | check `gpt-5` / `gpt-5-mini` | Must match model names that exist on the user's OpenAI account |
| `CMO2_AI_COMPANY_MONTHLY_TOKEN_CEILING` | e.g. `2000000` | Per-customer cost cap. A batch stops cleanly when it's reached |

If a key is missing or wrong, calls fail with a clear "no API key configured" or provider error.
They never quietly fall back to demo text.

## 2. GoHighLevel: an OAuth app, not an "agency key"
CMO² never takes a GHL API key. It connects through your own **GHL Marketplace app**, which needs
**Agency Pro**:
1. Create the app at marketplace.gohighlevel.com with "Who can install" set to Agency, and enable
   the scopes listed in `.env.example`.
2. Set `CMO2_GHL_CLIENT_ID`, `CMO2_GHL_CLIENT_SECRET`, `CMO2_GHL_INSTALL_URL`,
   `CMO2_GHL_REDIRECT_URI` (it must exactly match the redirect URL registered on the app) and
   `CMO2_GHL_AGENCY_COMPANY_ID`.
3. Set **`CMO2_GHL_DEMO_MODE=false`**. Otherwise the figures stay simulated.
4. Restart. In CMO² go to Settings → connect GoHighLevel, approve in GHL, open **Customers**, then
   **Enable** each sub-account.

## 3. Run without the demo profile, on a real database
- **Don't set `SPRING_PROFILES_ACTIVE=demo`.** It seeds fake customers with simulated GHL
  connections and users whose password is known.
- Use the real `cmo2` MySQL database (or a fresh one). Register once: the first sign-up becomes the
  owner, and everyone else is invited.

## What works at each stage
| Configured | What works for real |
|---|---|
| AI keys + provider only | Content Studio ideas and drafts, cycle plans, translations. Drafts can be copied; no GHL publishing or real figures |
| + GHL app connected | Real sub-accounts and figures, approved posts scheduled in GHL Social Planner |
| + Notion/Confluence/Linear/Slack (Settings → Workspace tools) | Cycle records and Slack summaries in the owner's own tools |

## Known risks on the first live run (never verified against the real services)
- GHL scope names and the Social Planner post body: check them against GHL's current docs.
- OpenAI model names (above).
- The Phase 4 email spike (can GHL's public API create campaigns?) is still undecided.

## Verification, once the user has set the values
1. Start the backend with no `demo` profile. The log shows no DEMO banner.
2. Content Studio: add a short source, then **Find ideas**. On a customer's page, AI calls shows
   model `claude-opus-5-5` with no "(demo)" tag.
3. Generate one LinkedIn post. AI calls shows the OpenAI model, and the draft is new text, not the
   demo template.
4. GHL: Settings → connect, then Customers lists the real sub-accounts. Enable one; after a sync
   the dashboard figures match GHL.
5. Approve one post and schedule it in GHL. It appears in that sub-account's Social Planner.
