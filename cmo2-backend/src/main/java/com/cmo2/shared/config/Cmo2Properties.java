package com.cmo2.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed binding of the {@code cmo2.*} application config.
 */
@ConfigurationProperties(prefix = "cmo2")
public record Cmo2Properties(
        Jwt jwt,
        Crypto crypto,
        Security security,
        Cors cors
) {
    public record Jwt(
            String secret,
            long accessTokenTtlSeconds,
            long refreshTokenTtlSeconds,
            String issuer
    ) {}

    public record Crypto(String masterKey) {}

    public record Security(int maxFailedLogins, int lockoutMinutes) {}

    public record Cors(String allowedOrigins) {}
}
