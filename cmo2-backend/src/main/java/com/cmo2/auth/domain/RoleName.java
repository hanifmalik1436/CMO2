package com.cmo2.auth.domain;

/**
 * The five RBAC roles, highest privilege first:
 * OWNER &gt; ADMIN &gt; STRATEGIST &gt; ANALYST &gt; VIEWER.
 */
public enum RoleName {
    OWNER,
    ADMIN,
    STRATEGIST,
    ANALYST,
    VIEWER
}
