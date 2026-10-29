package com.cmo2.integrations.gohighlevel.capability;

import com.cmo2.shared.exceptions.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Decides whether an actor may run a capability right now, based on its risk tier. */
@Component
public class CapabilityGuard {

    private static final Set<String> DESTRUCTIVE_ROLES = Set.of("ROLE_OWNER", "ROLE_ADMIN");

    /** Throws 403 if the action is not allowed; returns normally if it is. */
    public void check(GhlCapability capability, Actor actor, String approvalRef) {
        switch (capability.tier) {
            case READ, REVERSIBLE_WRITE -> { }
            case CLIENT_FACING -> {
                if (approvalRef == null || approvalRef.isBlank()) {
                    throw ApiException.forbidden(capability + " reaches the customer's audience and needs an approval first");
                }
            }
            case DESTRUCTIVE -> {
                if (actor != Actor.HUMAN) {
                    throw ApiException.forbidden(capability + " is destructive; AI agents can never run it");
                }
                if (!currentUserHasAnyRole()) {
                    throw ApiException.forbidden(capability + " is destructive and needs a signed-in owner or admin");
                }
            }
        }
    }

    private boolean currentUserHasAnyRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (DESTRUCTIVE_ROLES.contains(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
