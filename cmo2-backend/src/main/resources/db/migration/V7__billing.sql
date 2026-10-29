-- =====================================================================
-- CMO2 :: V7 :: Billing (Phase 7)
--
-- Subscription lifecycle lives on the tenant (status/plan_code/stripe_customer_id
-- already exist from V1). This adds the Stripe subscription id and the current paid
-- period end so webhooks can drive active -> past_due -> suspended/canceled.
--
-- Raw Stripe webhooks reuse the existing webhook_events table (source = 'STRIPE');
-- AI/usage metering reuses usage_records. No new tables are required.
-- =====================================================================

ALTER TABLE tenants
    ADD COLUMN stripe_subscription_id VARCHAR(64) NULL AFTER stripe_customer_id,
    ADD COLUMN current_period_end     DATETIME    NULL AFTER trial_ends_at;

-- Webhook handlers resolve a tenant from the Stripe customer/subscription id.
CREATE INDEX idx_tenant_stripe_customer     ON tenants (stripe_customer_id);
CREATE INDEX idx_tenant_stripe_subscription ON tenants (stripe_subscription_id);
