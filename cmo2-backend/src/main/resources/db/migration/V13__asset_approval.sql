-- =====================================================================
-- CMO2 :: V13 :: Draft → approval → publish
--
-- Every generated asset now starts as PENDING_APPROVAL. Only the owner
-- or an admin can approve or reject it, and nothing reaches a
-- customer's audience until it is APPROVED.
--
-- assets: who reviewed the draft, when, and the reason for a rejection.
-- asset_deployments: when a post is scheduled to go out and which
-- social accounts it targets. UNCONFIRMED marks a publish GoHighLevel
-- never answered, so a retry has to be confirmed by a person.
-- =====================================================================

ALTER TABLE assets
    MODIFY COLUMN status VARCHAR(24) NOT NULL DEFAULT 'PENDING_APPROVAL', -- PENDING_APPROVAL, APPROVED, REJECTED, DEPLOYED, ARCHIVED (GENERATED/TRANSLATED: legacy)
    ADD COLUMN reviewed_by BIGINT        NULL AFTER tokens_used,
    ADD COLUMN reviewed_at DATETIME      NULL AFTER reviewed_by,
    ADD COLUMN review_note VARCHAR(1000) NULL AFTER reviewed_at,
    ADD KEY idx_assets_status (tenant_id, status, created_at);

ALTER TABLE asset_deployments
    MODIFY COLUMN status VARCHAR(24) NOT NULL DEFAULT 'PENDING',          -- PENDING, ACCEPTED, FAILED, UNCONFIRMED
    ADD COLUMN scheduled_for   DATETIME      NULL AFTER deployed_at,
    ADD COLUMN target_accounts VARCHAR(2000) NULL AFTER scheduled_for;    -- comma-separated GHL social account ids

-- Drafts written before approvals existed were never approved: queue them for review.
UPDATE assets SET status = 'PENDING_APPROVAL' WHERE status IN ('GENERATED', 'TRANSLATED');
