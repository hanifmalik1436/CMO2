package com.cmo2.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Self-serve signup. Creates a new tenant (the organization) plus its first
 * OWNER user. {@code tenant_id} is never accepted from the client.
 */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 190) String email,
        @NotBlank @Size(min = 10, max = 100, message = "Password must be 10–100 characters") String password,
        @NotBlank @Size(max = 160) String fullName,
        @NotBlank @Size(max = 160) String organizationName
) {}
