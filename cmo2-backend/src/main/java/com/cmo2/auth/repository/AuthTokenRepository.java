package com.cmo2.auth.repository;

import com.cmo2.auth.domain.AuthToken;
import com.cmo2.auth.domain.AuthTokenType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {

    /**
     * Token lookup happens before tenant context is bound (e.g. clicking a verification
     * link, presenting a refresh token), so we resolve by the globally-unique hash.
     */
    Optional<AuthToken> findByTokenHashAndType(String tokenHash, AuthTokenType type);
}
