-- =====================================================================
-- V20: Content Studio library and generator
-- (docs/plans/2026-11-22-content-studio-redesign.md).
--
-- content_sources: where the idea search stands (sources are analysed
-- in the background as soon as they're added), favourites, when the
-- source was last used for a batch, and where it came from (a web link
-- or an uploaded file). kind now also allows RECORDING, UPLOAD, LINK.
-- Voice recordings are transcribed and the audio is never stored.
--
-- content_ideas: how strong the idea's hook is (0-100) and why.
--
-- assets: Smart Schedule's suggested publishing time. Only a suggestion:
-- nothing is scheduled in GoHighLevel until the owner approves the draft.
-- =====================================================================

ALTER TABLE content_sources
    ADD COLUMN status       VARCHAR(16)   NOT NULL DEFAULT 'READY' AFTER kind,   -- PENDING, ANALYZING, READY, TOO_SHORT, FAILED
    ADD COLUMN status_note  VARCHAR(500)  NULL AFTER status,
    ADD COLUMN starred      BIT(1)        NOT NULL DEFAULT b'0' AFTER status_note,
    ADD COLUMN last_used_at DATETIME      NULL AFTER analyzed_at,
    ADD COLUMN origin_url   VARCHAR(2048) NULL AFTER last_used_at,
    ADD COLUMN origin_file  VARCHAR(255)  NULL AFTER origin_url;

ALTER TABLE content_ideas
    ADD COLUMN virality_score  INT          NULL AFTER quote,
    ADD COLUMN virality_reason VARCHAR(300) NULL AFTER virality_score;

ALTER TABLE assets
    ADD COLUMN suggested_publish_at DATETIME NULL AFTER idea_id;
