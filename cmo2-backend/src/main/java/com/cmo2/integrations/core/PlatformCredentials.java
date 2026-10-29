package com.cmo2.integrations.core;

import java.time.Instant;

/**
 * Decrypted credentials handed to a connector for the duration of a call.
 * Stored encrypted at rest (AES-256-GCM); decrypted by the service layer just-in-time
 * and never logged or returned to clients.
 *
 * <p>{@code apiKey} serves a static key (a GHL Private Integration Token, or a demo key).
 * {@code accessToken}/{@code expiresAt} serve a short-lived OAuth token — for GHL, a
 * per-location token minted from the agency connection.
 */
public record PlatformCredentials(
        String apiKey,
        String locationId,
        String accessToken,
        String refreshToken,
        Instant expiresAt,
        String clientSecret
) {
    public static PlatformCredentials staticKey(String apiKey, String locationId) {
        return new PlatformCredentials(apiKey, locationId, null, null, null, null);
    }

    public static PlatformCredentials oauth(String accessToken, String locationId, Instant expiresAt) {
        return new PlatformCredentials(null, locationId, accessToken, null, expiresAt, null);
    }

    public boolean isOAuth() {
        return accessToken != null;
    }

    /** The token to send as {@code Authorization: Bearer}. */
    public String bearerToken() {
        return accessToken != null ? accessToken : apiKey;
    }
}
