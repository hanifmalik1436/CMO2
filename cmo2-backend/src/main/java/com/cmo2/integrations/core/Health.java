package com.cmo2.integrations.core;

/** Traffic-light health for a platform connection. */
public enum Health {
    GREEN,   // healthy, recent successful sync
    YELLOW,  // degraded / not yet synced / slow
    RED      // failing (auth error, unreachable, repeated failures)
}
