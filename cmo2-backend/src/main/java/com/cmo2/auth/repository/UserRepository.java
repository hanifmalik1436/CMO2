package com.cmo2.auth.repository;

import com.cmo2.auth.domain.User;
import com.cmo2.auth.domain.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Used at login, before any tenant context exists. Email is globally unique,
     * so this resolves the single matching account (and its tenant).
     */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    long countByTenantId(Long tenantId);

    /** Occupied seats for a tenant (plan-limit enforcement) — disabled members don't count. */
    long countByTenantIdAndStatusNot(Long tenantId, UserStatus status);

    /** Team roster for the tenant, newest first. */
    List<User> findByTenantIdOrderByCreatedAtAsc(Long tenantId);
}
