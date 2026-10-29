package com.cmo2.shared.websocket;

import java.security.Principal;

/** Minimal STOMP principal carrying the authenticated user + tenant. */
public record StompPrincipal(Long userId, Long tenantId) implements Principal {
    @Override
    public String getName() {
        return String.valueOf(userId);
    }
}
