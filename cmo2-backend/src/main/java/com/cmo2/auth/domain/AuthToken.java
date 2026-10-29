package com.cmo2.auth.domain;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Single-use, expiring token used for email verification, password reset, and
 * refresh-token rotation. Only the SHA-256 hash of the opaque token is stored —
 * the raw value exists only in transit / in the client cookie.
 */
@Entity
@Table(name = "auth_tokens")
@Getter
@Setter
public class AuthToken extends BaseTenantEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 100)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AuthTokenType type;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    public boolean isUsable() {
        return consumedAt == null && expiresAt.isAfter(Instant.now());
    }
}
