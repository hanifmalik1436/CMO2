package com.cmo2.integrations.core;

/** Result of a connection test: a traffic-light status plus a human-readable reason. */
public record ConnectionHealth(Health status, String message, Integer latencyMs) {

    public static ConnectionHealth green(String message, Integer latencyMs) {
        return new ConnectionHealth(Health.GREEN, message, latencyMs);
    }

    public static ConnectionHealth yellow(String message) {
        return new ConnectionHealth(Health.YELLOW, message, null);
    }

    public static ConnectionHealth red(String message) {
        return new ConnectionHealth(Health.RED, message, null);
    }
}
