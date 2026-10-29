-- GoHighLevel webhooks are verified against GHL's published Ed25519 public key
-- (see GhlSignatureVerifier), not a per-connection shared secret. This column was
-- wired end-to-end in the backend but never reachable from the UI, so it was always
-- unused in practice. Dropping it along with the dead code that read/wrote it.
ALTER TABLE platform_connections DROP COLUMN webhook_secret_enc;
