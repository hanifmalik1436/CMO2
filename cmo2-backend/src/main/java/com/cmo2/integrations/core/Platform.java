package com.cmo2.integrations.core;

/**
 * GoHighLevel is now the only integratable platform — every client's other marketing
 * platforms (social, ads, outreach) are connected inside their GHL sub-account's own
 * native panel instead of through a direct CMO2 connector. The 13 former connectors
 * (ReachInbox, Nureply, Sendr, Leadpro, ManyChat, SlyBroadcast, LinkedIn, Facebook,
 * Instagram, Google Ads, TikTok, Stripe-data, PayPal) were retired with the GHL
 * agency/sub-account pivot.
 */
public enum Platform {
    GOHIGHLEVEL("GoHighLevel");

    public final String displayName;

    Platform(String displayName) {
        this.displayName = displayName;
    }
}
