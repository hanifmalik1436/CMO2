# Content Studio redesign (ContentMagic-style)

**Status:** Built (22 Nov 2026). Not yet verified against live OpenAI transcription or real web
pages; the frontend builds but hasn't been clicked through in a browser.

**Also fixed on the way:** the test profile now pins the demo AI, blank AI keys and agency GHL
mode. Before, tests picked up the local `application.properties` and called the real Claude API,
which made them slow and caused the 14 "pre-existing" failures.

## Context

The owner wants Content Studio to work like the ContentMagic design they shared (`studio.html`):
a start screen with a record orb and a row of "bring in a source" options, a Content Library
table of sources, and an "Advanced Content Generator" (ideas × formats × drafts with a totals
bar). Content comes from the source, the customer's profile and approved canon (drift-control
step 1, already built), and AI-found ideas with strong hooks.

Most of the engine exists (`studio/`: sources → ideas → batches → Approvals, 15 formats, 1–3
drafts, full-source option, 40 pieces max). This work redesigns the screens and adds what the
design has and CMO² lacks. Keep CMO²'s own menu bar (no left sidebar from the design), CMO²'s
dark glass styling, and no ContentMagic name or logo.

Decisions made with the owner (22 Nov 2026):
- **Voice recordings:** transcribed through OpenAI, then the audio is thrown away; only the
  transcript is stored.
- **Virality:** each idea gets a 0–100 hook-strength score and a one-line reason (pattern used),
  sorted best first. The approved canon always wins over a "viral" hook; no promises in the UI.

Out of scope (shown as "Soon" like the design): Record Video, Group Capture, Reaction, Search
Optimization, video files and YouTube links. Learning from post engagement waits for GHL post
statistics (unverified).

Strange Loop stage: 1 (Innovation).

## Backend (`cmo2-backend/src/main/java/com/cmo2/`)

### 1. Migration `V20__content_studio_library.sql`
- `content_sources`: `status` VARCHAR(16) NOT NULL DEFAULT 'READY' (PENDING, ANALYZING, READY,
  TOO_SHORT, FAILED), `status_note` VARCHAR(500), `starred` BIT(1) NOT NULL DEFAULT 0,
  `last_used_at` DATETIME, `origin_url` VARCHAR(2048), `origin_file` VARCHAR(255). `kind` now also
  allows RECORDING, UPLOAD, LINK. Existing rows: READY if analyzed, else PENDING→ set READY.
- `content_ideas`: `virality_score` INT NULL, `virality_reason` VARCHAR(300) NULL.
- `assets`: `suggested_publish_at` DATETIME NULL.
- Entities updated to match (`studio/domain/ContentSource`, `ContentIdea`, `assets/domain/Asset`);
  `INT` for Integer, `BIT(1)` for boolean.

### 2. Background analysis with status (`studio/service/StudioService`, new `SourceAnalysisRunner`)
- Every new source (paste, blank canvas, upload, link, recording) is analysed automatically in
  the background on `studioTaskExecutor` (same submit-after-commit pattern as
  `ContentBatchRunner.submit`; tenant named explicitly, `AiCallScope.forCompany`, work in a
  `REQUIRES_NEW` method). Status PENDING → ANALYZING → READY / FAILED (note kept).
- Text under `cmo2.studio.min-analyze-chars` (default 300) → TOO_SHORT, no AI call; drafts from
  the full source still allowed.
- Existing `POST /studio/sources/{id}/analyze` (Re-analyze) stays synchronous and also sets status.
- Fix the N+1 in `view()`: idea counts and "generated" counts by grouped queries
  (`ContentIdeaRepository` count by source; sum of `content_batches.completed` by source).
- `startBatch` sets the source's `last_used_at`.

### 3. Virality score (`ai/PromptRegistry.forContentIdeas`, `StudioService.ideasSchema`)
- Schema adds `viralityScore` (integer 0–100) and `viralityReason` (≤200 chars, names the hook
  pattern: curiosity gap, contrarian, specific number, story, mistake, how-to…).
- Prompt: score hook strength for this customer's audience; never at the expense of the canon or
  banned topics; ideas returned best first. Stored and returned in `IdeaView`.

### 4. New ways in (`studio/controller/StudioController`, new `studio/service/SourceIntake`)
| Endpoint | What it does |
|---|---|
| `POST /api/companies/{id}/studio/sources` | unchanged (paste text / blank canvas), now auto-analyses |
| `POST /api/companies/{id}/studio/sources/upload` (multipart `file`, `title?`) | .txt/.md read as text; .pdf via PDFBox; .docx via POI; audio (webm, m4a, mp3, wav, ogg) → transcription. Kind UPLOAD (RECORDING for audio). Max 25 MB |
| `POST /api/companies/{id}/studio/sources/link` (`url`, `title?`) | fetch an https/http page (jsoup), keep the article text, title from the page. Kind LINK, `origin_url` stored |
| `POST /api/companies/{id}/studio/sources/recording` (multipart `audio`) | transcribe, store transcript, kind RECORDING; audio never stored |
| `PUT /api/studio/sources/{id}/star` (`starred`) | favourite |
- Link guard (SSRF): only http/https, resolve the host and refuse private, loopback,
  link-local and metadata addresses; 10 s timeout, 5 MB cap, redirects re-checked. Setting
  `cmo2.studio.allow-private-links` (default false; tests turn it on for a local server).
- Text over 100,000 characters is cut with a note, as pasted text is.
- Multipart limits set in code (`StudioConfig` `MultipartConfigElement`, 25 MB), because
  `application.properties` is git-ignored.
- Roles: OWNER/ADMIN/STRATEGIST create; all roles read. Audit `CONTENT_SOURCE_ADDED` with kind.
- New dependencies in `pom.xml`: `org.apache.pdfbox:pdfbox` 3.0.3, `org.apache.poi:poi-ooxml`
  5.3.0 (both already in the local Maven cache), `org.jsoup:jsoup` (download needed).

### 5. Transcription through `AiService`
- `ai/AiService.transcribe(byte[] audio, String filename, String mimeType)`: same budget checks
  and call log as `generate` (kind `TRANSCRIPTION`, budget tokens estimated from transcript
  length since speech-to-text isn't billed in tokens).
- `ai/provider/OpenAiProvider.transcribe`: multipart POST to `/audio/transcriptions`, model from
  `cmo2.ai.openai.transcription-model` (default `whisper-1`). `StubAiProvider` returns a demo
  transcript. Claude can't transcribe: without an OpenAI key the endpoint answers with a clear
  "needs an OpenAI key" error.
- `.env.example`: `CMO2_AI_OPENAI_TRANSCRIPTION_MODEL`.

### 6. More formats (`assets/domain/ContentFormat`)
Add, all copy & paste (not GHL-publishable): `X_LONG_POST`, `X_ARTICLE`, `LINKEDIN_ARTICLE`,
`LINKEDIN_NEWSLETTER`, `YOUTUBE_OUTLINE`, `YOUTUBE_COMMUNITY_POST`, `SITE_NEWSLETTER`,
`SITE_LEAD_MAGNET`, `SUBSTACK_POST`, each with its own guidance and char limit; matching stub
output in `StubContentWriter`.

### 7. Smart Schedule (`BatchRequest.smartSchedule`, `ContentBatchRunner`)
- When on, each publishable draft gets `suggested_publish_at`: spread over the next 14 days in
  the customer's timezone, at most one post per platform per day, at fixed good slots per
  platform (e.g. LinkedIn 08:30 weekdays, Instagram 12:00, Facebook 13:00, X 09:00, Google 10:00).
  Computed in Java, no AI.
- Nothing is scheduled in GHL: Approvals shows the suggested time and pre-fills it in the
  publish form; publishing still needs approval.

## Frontend (`cmo2-frontend/src/features/studio/`)

Split `ContentStudioPage.tsx` into a small router page and components; tabs **Studio · Content
Library** at the top, Generator opened from a source's **Create** button (`/content-studio`,
`?view=library|generator&sourceId=`). Customer picker stays in the page header. Dark canvas like
the design, our `GlassCard`/buttons/colours.

| File | Contents |
|---|---|
| `ContentStudioPage.tsx` | header (customer picker, tabs), view switching via URL params |
| `StudioStart.tsx` | "Hey {first name}, let's create together", record orb (CSS port of the design's orb, our brand blue), recording/paused states with timer and live waveform (MediaRecorder + AnalyserNode, simulated wave if the mic is refused), Finish & Save / Pause; "or bring in something you already have" dock: Paste Text, Upload File, Blank Canvas, Add From Link (+ "Soon" items), pins saved in localStorage; saving screen, then go to the Library |
| `SourceDialogs.tsx` | paste / blank canvas editor, link form, upload picker |
| `ContentLibrary.tsx` | search, favourites filter, sort newest/oldest; table: title + status pill (Finding ideas…, Too short, Failed), source icon, created, last used, generated, star, **Create →**, more (delete for owner/admin). Polls every 2 s while any source is PENDING/ANALYZING |
| `ContentGenerator.tsx` | "Writing for {customer} · canon vN" line, Full Transcript card, Viral Ideas (n/m) with collapse, Select All, Count, Re-analyze, idea cards with type, title, summary, hooks, quote and score badge + reason; right column: Smart Schedule switch, formats by platform with "Select all" per platform, drafts each, Spanish; fixed bottom bar (sources, formats, drafts each, total, Generate batch, 40 max) and batch progress with a link to Approvals |
| `studioApi.ts`, `lib/types.ts` | new calls and fields |
| `features/assets/ApprovalsPage.tsx` | show "Suggested: {time}" and pre-fill the publish time |

Loading / error / empty states on every view; write actions wrapped in `RequireRole`.

## Tests (`cmo2-backend/src/test/java/com/cmo2/studio/`)
- Flow: a pasted source is analysed in the background (status reaches READY, ideas have scores,
  best first); a short source is TOO_SHORT with no AI call; star toggles; Create batch sets
  last used; generated count adds up; Smart Schedule gives future times, one per platform per
  day, in the customer's timezone; publishing still requires approval.
- Upload: .txt, .pdf (made with OpenPDF in the test), .docx (made with POI) become text; an
  unsupported type is a 400; audio goes through the stub transcription.
- Link: a local `HttpServer` page is fetched and stripped (private links allowed in that test);
  a link to 127.0.0.1 / 169.254.169.254 is refused when the guard is on.
- Provider wire test: OpenAI transcription multipart request and response.
- Isolation and roles for every new endpoint: other agency → 404; VIEWER → `AccessDeniedException`.
- Update `ContentStudioLiveRoutingTest` / `ContentStudioFlowTest` for the new idea fields.

## Docs
`docs/PROJECT_STATE.md` (built, unverified: live transcription, link fetching on real sites),
`CLAUDE.md` (next migration V21), `.env.example`.

## Verification
1. `mvn -o clean test` (online once for jsoup): new tests pass; the same 14 pre-existing failures
   as before (local `application.properties` sets single-subaccount GHL mode and the loop tests
   time out), nothing new failing.
2. `npm run build` passes.
3. Demo run on in-memory H2 (`spring-boot:test-run`, profiles `test,demo`) + `npm run dev`:
   paste a source → Library shows "Finding ideas…" then ideas with scores; record 10 s with the
   mic → transcript (demo text without an OpenAI key); upload a PDF; add a link; Create → pick
   ideas and formats with Smart Schedule on → drafts in Approvals with suggested times.
