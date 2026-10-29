package com.cmo2.shared.ratelimit;

import com.cmo2.shared.security.TenantContext;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token-bucket rate limiting per tenant (when authenticated) or per client IP (otherwise),
 * protecting the API — and the AI cost budget — from bursts/abuse. Runs after the JWT filter
 * so the tenant is known. Buckets are held in-memory (Bucket4j); a Redis-backed proxy manager
 * is the drop-in for multi-instance deployments (the stack already runs Redis).
 *
 * <p>Webhooks, health, and API docs are exempt (Stripe/GHL may legitimately burst).
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final boolean enabled;
    private final long capacity;
    private final long refillPerMinute;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(
            @Value("${cmo2.ratelimit.enabled:true}") boolean enabled,
            @Value("${cmo2.ratelimit.capacity:240}") long capacity,
            @Value("${cmo2.ratelimit.refill-per-minute:240}") long refillPerMinute) {
        this.enabled = enabled;
        this.capacity = capacity;
        this.refillPerMinute = refillPerMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) return true;
        String path = request.getRequestURI();
        return path.startsWith("/api/webhooks/")
                || path.startsWith("/actuator/")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui")
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Bucket bucket = buckets.computeIfAbsent(keyFor(request), k -> newBucket());
        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded. Please slow down.\"}");
        }
    }

    private String keyFor(HttpServletRequest request) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            return "tenant:" + tenantId;
        }
        return "ip:" + clientIp(request);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(refillPerMinute, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }
}
