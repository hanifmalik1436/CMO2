package com.cmo2.auth.controller;

import com.cmo2.auth.domain.User;
import com.cmo2.auth.dto.UserSummary;
import com.cmo2.auth.repository.UserRepository;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.security.AuthPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Returns the authenticated user — used by the SPA to rehydrate session on reload.
 * Authenticated by default (not under /api/auth/**).
 */
@RestController
@RequestMapping("/api/me")
@Tag(name = "Current user")
public class CurrentUserController {

    private final UserRepository userRepository;

    public CurrentUserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping
    @Operation(summary = "Get the current authenticated user")
    public UserSummary me(@AuthenticationPrincipal AuthPrincipal principal) {
        User user = userRepository.findById(principal.userId())
                .orElseThrow(() -> ApiException.unauthorized("Account no longer exists"));
        return new UserSummary(
                user.getId(), user.getTenantId(), user.getEmail(), user.getFullName(),
                user.isEmailVerified(),
                user.getRoles().stream().map(r -> r.getName().name()).sorted().toList());
    }
}
