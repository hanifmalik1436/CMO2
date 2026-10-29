package com.cmo2.shared.security;

/**
 * The authenticated principal carried in the Spring Security context.
 * Exposes the user, the tenant, and the email resolved from the JWT.
 */
public record AuthPrincipal(Long userId, Long tenantId, String email) {
}
