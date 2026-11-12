# Per-customer AI memory at 1,000+ customers

**Status:** Not started. Planned 4 Nov 2026.

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
