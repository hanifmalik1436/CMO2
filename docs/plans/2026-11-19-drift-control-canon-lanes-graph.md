# Drift control: approved canon, lanes, drift check and graph

**Status:** Step 1 done (19 Nov 2026). Steps 2–5 not started.

**What step 1 changed from this plan:** the canon is one owner-written text (with versions) on
top of the existing profile fields, so nothing was back-filled: the profile stays as the facts and
needs no copy. The task's own instructions stay in the system prompt, as a second block after the
cached customer context, rather than moving to the user prompt; the cache only needs the context
first. Drafts and Content Studio no longer get the weekly figures (they never needed them); the
cycle plan and strategist chats still do.

**How to use this file:** give it to Claude and name the step to implement (for example "implement
step 1 of the drift-control plan"). Do the steps in order; each one stands on its own and ends with
its own check. Backend paths are under `cmo2-backend/src/main/java/com/cmo2/`.

**Related plans:**
- `docs/plans/2026-11-04-per-customer-ai-memory.md` §4 named the ideas (versioned identity, layer
  order, drift check). This file is the detailed version of those three bullets. The "long-term
  lessons" bullet of §4 stays in that plan. §1 (memory block) and §3 (isolation test) are still
  needed and fit after step 1 here.
- `docs/plans/2026-11-12-architecture-review-and-gaps.md`: B3 (AI accounting) overlaps step 1's
  cache-token accounting; P3 (customer-level isolation) is the guard every new query here must use.

---

## Context

CMO² uses one Claude key and one OpenAI key for every customer. The providers remember nothing
between calls, so a customer's memory is only what CMO² puts in each prompt, and keeping customers
apart is entirely CMO²'s job (filter by `company_id` on every query).

Drift comes from how the prompt is organised:
- Each cycle loads a summary of earlier cycles, and the next summary is written from that one, so
  small shifts add up.
- The anchor, the identity block (`customers/service/CustomerIdentityService.java`), is short: a few
  profile fields plus owner's notes capped at 2,000 characters (`MAX_NOTES`). For a customer like
  Texas Property Tax Advocates, the brand lock, two lanes with opposite tone weightings
  (Estate: 45% empathy; Judgment/Foreclosure: 45% urgency) and the banned phrases don't fit, and
  what doesn't fit drifts first.
- The identity block also carries this week's figures, which change every sync, and every prompt
  opens with a task-specific sentence before it. Both stop prompt caching from working.

The decision (discussed 17–19 Nov 2026): fix the organisation of the prompt first, not the memory
technology.
1. A versioned, owner-approved **canon** per customer, sent in full on every call and **cached**.
2. **Lanes, offers and claims** as real rows, with each draft tagged to its lane.
3. A cheap **drift check** of every draft against the canon (Claude Haiku 5.5).
4. A **graph view** of those entities on the customer page, built without AI.
5. **Vector search later**, only for "don't repeat yourself" and finding old posts.

Rejected: Anthropic's memory tool (extra round trips, and the model rewriting its own memory is
drift by design) and AI-extracted knowledge graphs (cost tokens, add extraction errors).

---

## Step 1 — Approved canon, sent in full and cached

**What it does:** the owner writes and approves the customer's canon (positioning, brand voice,
rules, banned claims, required lines such as disclaimers, 3–5 example posts). Only the approved
version reaches the AI. Every prompt is ordered so the stable part can be cached:

```
system:  [shared rules for this task family] [customer canon]  <- cache marker
user:    [this week's figures] [memory / DATA sections] [the task]
```

**Rules**
- Nothing edits the canon automatically. A new version is a draft until the owner approves it.
- Up to about 5,000 tokens of canon; the existing profile fields become the first version.
- Isolation: canon rows are read with `findByTenantIdAndCompanyId…`; a cache hit needs
  byte-identical text, so one customer's cached canon can never serve another.
- The canon must contain no timestamps, counts or figures, or it stops caching.

**Files**

| File | Change |
|---|---|
| New `db/migration/V19__customer_canon.sql` | `customer_canon_versions` (tenant, company, version no., status DRAFT/APPROVED/RETIRED, content LONGTEXT, approved_by, approved_at); cache columns on `ai_interactions` (`cache_read_tokens`, `cache_write_tokens`). Back-fill version 1 from `companies` + `customer_profiles`. |
| New `customers/domain/CustomerCanon.java`, `customers/repository/CustomerCanonRepository.java`, `customers/service/CustomerCanonService.java` | propose, approve (OWNER/ADMIN), current approved, history. Audit events. |
| `customers/service/CustomerIdentityService.java` | build the identity block from the approved canon; move the weekly figures into a separate `figuresBlock(...)` that goes in the user prompt |
| `customers/controller/CustomerController.java`, `customers/dto/CustomerDtos.java` | canon endpoints: get current, list versions, propose, approve |
| `ai/AiMessages.java` | `AiRequest` gains a `cacheableSystemPrefix` (keep old constructors); `AiResult` gains cache read/write tokens |
| `ai/PromptRegistry.java` | reorder every prompt: shared rules → canon → (user) figures, DATA, task. The task-specific opening sentence moves to the user prompt |
| `ai/provider/AnthropicProvider.java` | send the system prompt as text blocks with `cache_control` after the canon block; read cache usage fields |
| `ai/provider/OpenAiProvider.java` | read `usage.prompt_tokens_details.cached_tokens` (OpenAI caches by prefix automatically) |
| `ai/domain/AiInteraction.java`, `ai/AiInteractionRecorder.java`, `ai/TokenAccountant.java` | record cache tokens; budgets count them correctly |
| Callers of `identityBlock(...)`: `strangeloop/stages/Stage1Innovation.java`, `Stage2Learning.java`, `Stage5Optimization.java`, `studio/service/StudioService.java`, `studio/service/ContentBatchRunner.java`, `assets/service/GenerationService.java` | pass figures separately where the stage uses them |
| `cmo2-frontend/src/features/customers/CustomerDetailPage.tsx`, `customersApi.ts`, `src/lib/types.ts` | canon editor with version history, diff against the approved version, Approve button |

**Check**
- Tests: approve flow; a VIEWER can't approve (`AccessDeniedException`); another agency gets 404;
  only the APPROVED version appears in a captured prompt (`@MockBean` provider).
- Provider unit test: the cache marker sits after the canon block, and the system prefix is
  byte-identical across the three loop stages for the same customer.
- Live (when keys are set): run one Content Studio batch; from the second draft on,
  `cache_read_tokens` > 0 in the AI call log.

## Step 2 — Lanes, offers and claims as entities

**What it does:** a customer can have lanes (e.g. Estate, Judgment/Foreclosure), each with its own
tone mix, code, audience and rules; offers; and claims marked approved or banned. They belong to a
canon version, so they are approved with it. Every draft records its lane, and the prompt carries
only that lane's rules on top of the canon.

**Files**

| File | Change |
|---|---|
| New `db/migration/V20__customer_lanes.sql` | `customer_lanes`, `customer_offers`, `customer_claims` (each with `canon_version_id`), `assets.lane_id` |
| New `customers/domain/CustomerLane.java`, `CustomerOffer.java`, `CustomerClaim.java` + repositories | owned by `CustomerCanonService` |
| `assets/domain/Asset.java`, assets response DTO | `laneId` |
| `assets/service/GenerationService.java` | `draftFromPrompt` takes a lane |
| `ai/PromptRegistry.java` | lane rules block after the canon; `CYCLE_PLAN_SCHEMA` lets each post name a lane (enum of that customer's lane ids, so the AI can't invent one) |
| `strangeloop/stages/Stage1Innovation.java` | pass each post's lane to its draft |
| `studio/dto/StudioDtos.java`, `studio/service/StudioService.java`, `studio/service/ContentBatchRunner.java` | lane chosen per batch |
| Frontend: `CustomerDetailPage.tsx` (lanes/offers/claims editor), `features/studio/ContentStudioPage.tsx` (lane picker), `features/assets/ApprovalsPage.tsx` (lane badge and filter) | |

**Check**
- A cycle plan for a customer with two lanes returns posts tagged with valid lane ids only.
- A draft for lane A has lane A's rules in its prompt and none of lane B's.
- A customer with no lanes works exactly as before.

## Step 3 — Drift check on every draft

**What it does:** after a draft is created, a cheap call (Claude Haiku 5.5, low effort) checks it
against the canon and its lane: tone, banned claims and phrases, required lines, invented offers or
results. The result is shown on the draft in Approvals and can be filtered. It flags; it never
blocks or rewrites. A failed check leaves the draft "unchecked", not rejected.

**Files**

| File | Change |
|---|---|
| `ai/AiModelTier.java` | new `CHECK` tier |
| `ai/AiRouter.java` | route `CHECK`; setting `cmo2.ai.model.check` (default `claude-haiku-5-5`) and `cmo2.ai.openai.model.check` |
| `ai/provider/AnthropicProvider.java` | effort LOW for `CHECK` |
| `ai/provider/StubAiProvider.java` | demo answer for the check |
| `ai/PromptRegistry.java` | `forDriftCheck(canon, laneRules, draft)` + schema: verdict (OK / FLAG), problems (list, each tied to a rule) |
| New `assets/service/DriftCheckService.java` | listens to `assets/events/AssetGeneratedEvent` (`@TransactionalEventListener(fallbackExecution = true)`, work in a `REQUIRES_NEW` method, exceptions caught), runs inside `AiCallScope.forCompany` |
| Migration (in V20 or V21) | `assets.drift_status` (UNCHECKED/OK/FLAGGED), `assets.drift_notes` |
| `assets/domain/Asset.java`, response DTO, `assets/service/ApprovalService.java` | expose the result; filter by flagged |
| Frontend: `features/assets/ApprovalsPage.tsx`, `assetsApi.ts` | flag marker with the reasons, "flagged only" filter |
| `.env.example` | the new model settings |

**Check**
- A stub draft containing a banned phrase is FLAGGED with that rule named.
- A provider error leaves the draft UNCHECKED and the draft is still created.
- The check's tokens count against that customer's monthly budget.
- Track the flag rate per customer; a rise over weeks is the drift signal.

## Step 4 — Graph view on the customer page

**What it does:** shows how the customer's canon, lanes, posts, experiments, results and lessons
connect, so the owner can see why the AI did something and spot drift early (for example
rejections piling up on one lane). Built from database rows only; no AI, no tokens.

**Files**

| File | Change |
|---|---|
| New `customers/service/CustomerGraphService.java` | nodes + edges for one customer from canon versions, lanes, assets (status, lane, drift flag), experiments, learning records; capped (e.g. last 90 days, 300 nodes) |
| `customers/controller/CustomerController.java`, `customers/dto/CustomerDtos.java` | `GET /api/customers/{id}/graph` |
| `cmo2-frontend/package.json` | add one graph library (React Flow `@xyflow/react`, or Cytoscape.js) |
| New `cmo2-frontend/src/features/customers/CustomerGraphPanel.tsx`; `CustomerDetailPage.tsx`, `customersApi.ts` | the panel, with loading / error / empty states |

**Check**
- Another agency gets 404; another customer's nodes never appear.
- A customer with no data shows the empty state, not an error.
- `npm run build` passes.

## Step 5 — Vector search (later; only when customers have enough history)

**What it does:** embeds approved posts so new drafts can avoid repeating earlier openings and the
drift check can catch near-duplicates. Not a drift fix by itself: it finds what is *similar*, not
what is *authoritative*.

**Files**

| File | Change |
|---|---|
| New migration | `content_embeddings` (tenant, company, asset, model, dims, vector VARBINARY) |
| `ai/AiService.java`, `ai/provider/AiProvider.java` | `embed(...)` through `AiService` (budget + call log); `OpenAiProvider` implements it, `AnthropicProvider` reports unsupported, the stub returns fixed vectors |
| New `assets/service/ContentEmbeddingService.java` | embed on approval (listens to `AssetReviewedEvent`) |
| New `assets/service/SimilarityService.java` | cosine similarity in Java over **one customer's** rows only (filter first, then compare); no vector database at this scale |
| `studio/service/ContentBatchRunner.java`, `ai/PromptRegistry.forContentPiece`, `DriftCheckService.java` | "don't repeat these openings" section; near-duplicate flag |

Do it together with the memory plan §1 (memory block), which covers the same "don't repeat" need
without embeddings.

**Check:** a draft that repeats an approved post's opening is flagged; another customer's posts
are never compared.

---

## Across all steps

- **Isolation test** (memory plan §3, extended): two customers in one agency, each with canon,
  lanes, drafts and embeddings. Customer A's prompts, graph and similarity results contain nothing
  of B's.
- **Every new endpoint:** another agency → 404, lower role → `AccessDeniedException`.
- **Strange Loop stage:** canon and lanes feed Stage 1 (Innovation); the drift check and graph are
  Stage 3 (Documentation) and Stage 4 (Reporting).
- **Docs:** update `docs/PROJECT_STATE.md`; in `CLAUDE.md` correct the next migration number
  (it says V17; V18 exists) and add the rule "the canon goes first in the system prompt and holds
  nothing that changes per call".
- `mvn -o clean test` and `npm run build` green after each step.

## Not doing (named)

- Anthropic's memory tool.
- AI-extracted knowledge graphs (GraphRAG) or a graph database.
- A vector database or moving off MySQL.
- Automatic canon edits of any kind.
