# Spike: can CMO² create and schedule customer emails through GoHighLevel's public API?

**Date:** 28 October 2026 · **Status:** open, needs the sandbox sub-account · **Decides:** plan Phase 4, email

## The question

After the owner approves an email draft in CMO², can CMO² put it into the customer's GHL
sub-account as a campaign (or template plus campaign) and schedule it, **using only the public
API** (agency OAuth app → location token)? Or must emails go through Snapshot workflows that read
GHL custom values?

No email-sending code is written until this is answered (plan rule).

## What the documentation says (desk research, today)

| Source | What it shows |
|---|---|
| GHL help centre, "Email Marketing V2 public APIs" (modified 13 May 2026) | Lists **Create / Update / Delete / Schedule Campaign V2**, **Templates V2** and **Statistics V2** as public APIs, with links into the marketplace docs (`/docs/ghl/emails/create-email-campaign-v-2`, `…/schedule-campaign-v-2`, `…/templates-v-2`). No beta label or plan requirement is mentioned. Legacy email APIs "will continue to function". |
| Marketplace docs pages for those endpoints | Not readable by automated fetch (they render in the browser with JavaScript). **Method, path, Version header, scopes and body fields are unknown.** |
| GHL's published OpenAPI repo, `apps/emails.json` (Version `2021-07-28`) | Only: `GET /emails/schedule` (list campaigns), `POST /emails/builder` (create template), `GET /emails/builder` (list templates), `POST /emails/builder/data` (update template), `DELETE /emails/builder/{locationId}/{templateId}`. **No create-campaign or schedule-campaign endpoint.** |
| `gohighlevel-cli` (reference only) | Creates campaigns only through GHL's internal, Firebase-login API, which CMO² must not use. Its `/emails/schedule/start` sends to a whole list in one call, which we've ruled out. |

**Reading so far:** the V2 campaign endpoints probably exist and are public, but their shape
can't be confirmed from here. The documented, versioned API only covers templates.

## What to test in the sandbox (about half a day)

Use the sandbox sub-account, with the Marketplace app installed and these scopes enabled:
`emails/builder.readonly`, `emails/builder.write`, and whatever scope the V2 campaign pages name
(look for an `emails/schedule…` or `campaigns…` scope).

1. **Read the V2 docs in a browser.** Note, for Create Campaign V2 and Schedule Campaign V2:
   method, path, `Version` header, scope, and every body field (subject, HTML body or template id,
   from name and address, recipients as tag / smart list / contacts, schedule time and its format).
2. **Template round trip** (documented today): `POST /emails/builder` with a location token, then
   `POST /emails/builder/data` to change its HTML, then confirm both in the GHL UI.
3. **Campaign V2 create**: create a campaign from that template, targeting a tag with one test
   contact (your own address). Confirm it appears as a draft in GHL and has **not** sent.
4. **Campaign V2 schedule**: schedule it ten minutes ahead. Confirm it waits, then sends to the
   one tagged contact only. Then check: can a scheduled campaign be cancelled through the API?
5. **Agency token check**: repeat step 3 with a token minted through `/oauth/locationToken`
   (the way CMO² will call it), not a Private Integration Token.
6. **Custom values fallback** (only if 3 or 4 fail): in a Snapshot workflow email, put
   `{{custom_values.cmo2_email_1_subject}}` and `{{custom_values.cmo2_email_1_body}}`, change the
   values with `PUT /locations/{id}/customValues/{id}`, trigger the workflow with a tag, and
   confirm the sent email shows the new text.

Write the results into the table below before any email code is written.

## Decision rule

- **Steps 3–5 all pass:** emails go through Campaign V2. Create is a tier-2 action that needs an
  approval (like social posts), scheduling is a separate tier-2 action, and targets are restricted
  to tags or smart lists the owner picked. Nothing ever calls `/emails/schedule/start`.
- **Step 3 or 4 fails, step 6 passes:** emails go through Snapshot workflows plus custom values
  (plan Phase 4, option B). Writing custom values is tier 1 and only happens after approval;
  `new-lead` / `booked-call` tags start and stop the sequence.
- **Both fail:** emails stay copy-and-paste. The approval queue already shows the approved text,
  and the publish panel says so.

## Results (fill in during the sandbox test)

| Step | Result | Notes (paths, headers, error bodies) |
|---|---|---|
| 1. V2 docs details | | |
| 2. Template round trip | | |
| 3. Campaign V2 create | | |
| 4. Campaign V2 schedule | | |
| 5. Agency-minted token | | |
| 6. Custom values in workflow email | | |

**Decision:** _pending_

## Sources

- [GHL help centre: Email Marketing V2 public APIs](https://help.gohighlevel.com/support/solutions/articles/155000007924-email-marketing-v2-public-apis)
- [GHL OpenAPI: apps/emails.json](https://raw.githubusercontent.com/GoHighLevel/highlevel-api-docs/main/apps/emails.json)
- [GHL marketplace docs: Create Campaign V2](https://marketplace.gohighlevel.com/docs/ghl/emails/create-email-campaign-v-2) (renders in a browser only)
