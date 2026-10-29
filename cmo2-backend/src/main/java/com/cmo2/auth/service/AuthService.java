package com.cmo2.auth.service;

import com.cmo2.auth.domain.AuthToken;
import com.cmo2.auth.domain.AuthTokenType;
import com.cmo2.auth.domain.Role;
import com.cmo2.auth.domain.RoleName;
import com.cmo2.auth.domain.User;
import com.cmo2.auth.domain.UserStatus;
import com.cmo2.auth.dto.AuthResponse;
import com.cmo2.auth.dto.LoginRequest;
import com.cmo2.auth.dto.RegisterRequest;
import com.cmo2.auth.dto.UserSummary;
import com.cmo2.auth.repository.AuthTokenRepository;
import com.cmo2.auth.repository.RoleRepository;
import com.cmo2.auth.repository.UserRepository;
import com.cmo2.shared.audit.AuditService;
import com.cmo2.shared.config.Cmo2Properties;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.mail.MailService;
import com.cmo2.shared.security.JwtService;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.shared.security.TokenHasher;
import com.cmo2.tenancy.domain.Tenant;
import com.cmo2.tenancy.service.TenantProvisioningService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Registration, login, refresh-token rotation, and email verification.
 * Enforces BCrypt hashing, account lockout, and single-use expiring tokens.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final AuthTokenRepository authTokenRepository;
    private final TenantProvisioningService tenantProvisioning;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final MailService mailService;
    private final AuditService auditService;
    private final Cmo2Properties props;
    private final String selfRegistration;

    public AuthService(UserRepository userRepository,
                       RoleRepository roleRepository,
                       AuthTokenRepository authTokenRepository,
                       TenantProvisioningService tenantProvisioning,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       MailService mailService,
                       AuditService auditService,
                       Cmo2Properties props,
                       @Value("${cmo2.auth.self-registration:first-user-only}") String selfRegistration) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.authTokenRepository = authTokenRepository;
        this.tenantProvisioning = tenantProvisioning;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.mailService = mailService;
        this.auditService = auditService;
        this.props = props;
        this.selfRegistration = selfRegistration == null ? "first-user-only" : selfRegistration.trim().toLowerCase();
    }

    /**
     * CMO² is an owner's console, so self-registration is closed by default once the first
     * account exists. {@code open} allows anyone to register; {@code closed} allows no one.
     */
    private void assertRegistrationAllowed() {
        switch (selfRegistration) {
            case "open" -> { }
            case "closed" -> throw ApiException.forbidden("Registration is closed");
            default -> {
                if (userRepository.count() > 0) {
                    throw ApiException.forbidden("Registration is closed. Ask the account owner to invite you.");
                }
            }
        }
    }

    /**
     * Creates a tenant + its first OWNER user, then issues a verification email.
     * The new user can log in immediately (verification gates sensitive actions later,
     * not basic access — keeps the "stranger signs up with zero help" flow smooth).
     */
    @Transactional
    public AuthResponse register(RegisterRequest req) {
        assertRegistrationAllowed();
        String email = req.email().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw ApiException.conflict("An account with that email already exists");
        }

        Tenant tenant = tenantProvisioning.createTrialTenant(req.organizationName().trim());

        // Bind context so the entity listener stamps tenant_id on the user + token.
        TenantContext.set(tenant.getId(), null);

        Role owner = roleRepository.findByName(RoleName.OWNER)
                .orElseThrow(() -> new IllegalStateException("OWNER role missing — check role seed data"));

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setFullName(req.fullName().trim());
        user.setStatus(UserStatus.ACTIVE);
        user.addRole(owner);
        user = userRepository.save(user);

        issueVerificationEmail(user);

        auditService.recordFor(tenant.getId(), user.getId(),
                "USER_REGISTERED", "User", String.valueOf(user.getId()),
                "Self-serve signup; tenant '" + tenant.getName() + "' created");

        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        String email = req.email().trim().toLowerCase();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> ApiException.unauthorized("Invalid email or password"));

        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
            throw ApiException.unauthorized("Account locked due to repeated failed logins. Try again later.");
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            throw ApiException.forbidden("This account has been disabled");
        }

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            registerFailedAttempt(user);
            throw ApiException.unauthorized("Invalid email or password");
        }

        // Success — reset lockout counters.
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(Instant.now());
        if (user.getStatus() == UserStatus.LOCKED) {
            user.setStatus(UserStatus.ACTIVE);
        }
        userRepository.save(user);

        auditService.recordFor(user.getTenantId(), user.getId(),
                "USER_LOGIN", "User", String.valueOf(user.getId()), "Successful login");

        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse refresh(String rawRefreshToken) {
        String hash = TokenHasher.hash(rawRefreshToken);
        AuthToken token = authTokenRepository.findByTokenHashAndType(hash, AuthTokenType.REFRESH)
                .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));

        if (!token.isUsable()) {
            throw ApiException.unauthorized("Refresh token expired or already used");
        }

        // Rotate: consume the presented token, issue a fresh pair.
        token.setConsumedAt(Instant.now());
        authTokenRepository.save(token);

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> ApiException.unauthorized("Account no longer exists"));

        return buildAuthResponse(user);
    }

    @Transactional
    public void verifyEmail(String rawToken) {
        String hash = TokenHasher.hash(rawToken);
        AuthToken token = authTokenRepository.findByTokenHashAndType(hash, AuthTokenType.EMAIL_VERIFICATION)
                .orElseThrow(() -> ApiException.badRequest("Invalid verification link"));
        if (!token.isUsable()) {
            throw ApiException.badRequest("Verification link expired or already used");
        }
        token.setConsumedAt(Instant.now());
        authTokenRepository.save(token);

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> ApiException.badRequest("Account no longer exists"));
        user.setEmailVerified(true);
        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            user.setStatus(UserStatus.ACTIVE);
        }
        userRepository.save(user);
    }

    /**
     * Accept a team invitation: the invitee sets their initial password, which activates and
     * verifies the account, and they're logged straight in. Public endpoint (no tenant context).
     */
    @Transactional
    public AuthResponse acceptInvite(String rawToken, String newPassword) {
        String hash = TokenHasher.hash(rawToken);
        AuthToken token = authTokenRepository.findByTokenHashAndType(hash, AuthTokenType.PASSWORD_RESET)
                .orElseThrow(() -> ApiException.badRequest("Invalid invite link"));
        if (!token.isUsable()) {
            throw ApiException.badRequest("Invite link expired or already used");
        }
        token.setConsumedAt(Instant.now());
        authTokenRepository.save(token);

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> ApiException.badRequest("Account no longer exists"));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setEmailVerified(true);
        user.setStatus(UserStatus.ACTIVE);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        auditService.recordFor(user.getTenantId(), user.getId(),
                "INVITE_ACCEPTED", "User", String.valueOf(user.getId()), "Invitee set password and joined");
        return buildAuthResponse(user);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        String hash = TokenHasher.hash(rawRefreshToken);
        authTokenRepository.findByTokenHashAndType(hash, AuthTokenType.REFRESH).ifPresent(t -> {
            t.setConsumedAt(Instant.now());
            authTokenRepository.save(t);
        });
    }

    // ---- helpers -------------------------------------------------------------

    private void registerFailedAttempt(User user) {
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);
        if (attempts >= props.security().maxFailedLogins()) {
            user.setStatus(UserStatus.LOCKED);
            user.setLockedUntil(Instant.now().plus(props.security().lockoutMinutes(), ChronoUnit.MINUTES));
        }
        userRepository.save(user);
        auditService.recordFor(user.getTenantId(), user.getId(),
                "USER_LOGIN_FAILED", "User", String.valueOf(user.getId()),
                "Failed login attempt #" + attempts);
    }

    private void issueVerificationEmail(User user) {
        String raw = TokenHasher.generateRawToken();
        AuthToken token = new AuthToken();
        token.setUserId(user.getId());
        token.setType(AuthTokenType.EMAIL_VERIFICATION);
        token.setTokenHash(TokenHasher.hash(raw));
        token.setExpiresAt(Instant.now().plus(48, ChronoUnit.HOURS));
        authTokenRepository.save(token);

        mailService.send(user.getEmail(), "Verify your CMO² account",
                "Welcome to CMO²! Confirm your email:\n\n"
                        + "http://localhost:5173/verify-email?token=" + raw
                        + "\n\nThis link expires in 48 hours.");
    }

    private AuthResponse buildAuthResponse(User user) {
        List<String> roles = user.getRoles().stream().map(r -> r.getName().name()).sorted().toList();
        String accessToken = jwtService.issueAccessToken(
                user.getId(), user.getTenantId(), user.getEmail(), roles);

        String rawRefresh = TokenHasher.generateRawToken();
        AuthToken refresh = new AuthToken();
        refresh.setTenantId(user.getTenantId());
        refresh.setUserId(user.getId());
        refresh.setType(AuthTokenType.REFRESH);
        refresh.setTokenHash(TokenHasher.hash(rawRefresh));
        refresh.setExpiresAt(Instant.now().plusSeconds(props.jwt().refreshTokenTtlSeconds()));
        authTokenRepository.save(refresh);

        UserSummary summary = new UserSummary(
                user.getId(), user.getTenantId(), user.getEmail(), user.getFullName(),
                user.isEmailVerified(), roles);

        return new AuthResponse(accessToken, rawRefresh, props.jwt().accessTokenTtlSeconds(), summary);
    }
}
