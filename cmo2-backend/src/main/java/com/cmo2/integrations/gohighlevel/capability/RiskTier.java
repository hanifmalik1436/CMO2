package com.cmo2.integrations.gohighlevel.capability;

/** How much harm an action can do, which decides who may run it and when. */
public enum RiskTier {
    /** Reads. Run automatically. */
    READ,
    /** Writes that can be undone (tags, custom values, workflow enrolment). Run automatically, always audited. */
    REVERSIBLE_WRITE,
    /** Anything a customer's audience sees (posts, messages, emails). Needs an approval first. */
    CLIENT_FACING,
    /** Deleting data, money, subscriptions. A signed-in OWNER/ADMIN only — never an AI agent. */
    DESTRUCTIVE
}
