package com.cmo2.auth.team;

import com.cmo2.auth.domain.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Request/response DTOs for team management. */
public final class TeamDtos {

    private TeamDtos() {}

    public record InviteRequest(
            @NotBlank @Email String email,
            @NotBlank String fullName,
            @NotBlank String role) {}

    public record ChangeRoleRequest(@NotBlank String role) {}

    public record AcceptInviteRequest(
            @NotBlank String token,
            @NotBlank @Size(min = 10, message = "Password must be at least 10 characters") String password) {}

    public record MemberResponse(
            Long id,
            String email,
            String fullName,
            String status,
            boolean emailVerified,
            List<String> roles,
            Instant lastLoginAt,
            Instant createdAt) {

        public static MemberResponse of(User u) {
            List<String> roles = u.getRoles().stream().map(r -> r.getName().name()).sorted().toList();
            return new MemberResponse(u.getId(), u.getEmail(), u.getFullName(), u.getStatus().name(),
                    u.isEmailVerified(), roles, u.getLastLoginAt(), u.getCreatedAt());
        }
    }
}
