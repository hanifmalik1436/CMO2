package com.cmo2.auth.dto;

import java.util.List;

public record UserSummary(
        Long id,
        Long tenantId,
        String email,
        String fullName,
        boolean emailVerified,
        List<String> roles
) {}
