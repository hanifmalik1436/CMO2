package com.cmo2.shared.security;

import com.cmo2.shared.config.Cmo2Properties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Issues and validates stateless JWT access tokens. Each token carries the
 * {@code tenant_id} claim (signed, tamper-proof) that powers multi-tenancy.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final String issuer;
    private final long accessTtlSeconds;

    public JwtService(Cmo2Properties props) {
        this.key = Keys.hmacShaKeyFor(props.jwt().secret().getBytes(StandardCharsets.UTF_8));
        this.issuer = props.jwt().issuer();
        this.accessTtlSeconds = props.jwt().accessTokenTtlSeconds();
    }

    public String issueAccessToken(Long userId, Long tenantId, String email, List<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(String.valueOf(userId))
                .claim("tenant_id", tenantId)
                .claim("email", email)
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTtlSeconds)))
                .signWith(key)
                .compact();
    }

    public ParsedToken parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        Long userId = Long.valueOf(claims.getSubject());
        Long tenantId = claims.get("tenant_id", Number.class).longValue();
        String email = claims.get("email", String.class);
        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class);
        return new ParsedToken(userId, tenantId, email, roles == null ? List.of() : roles);
    }

    public record ParsedToken(Long userId, Long tenantId, String email, List<String> roles) {}
}
