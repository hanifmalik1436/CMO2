package com.cmo2.auth.team;

import com.cmo2.auth.domain.AuthToken;
import com.cmo2.auth.domain.AuthTokenType;
import com.cmo2.auth.domain.Role;
import com.cmo2.auth.domain.RoleName;
import com.cmo2.auth.domain.User;
import com.cmo2.auth.domain.UserStatus;
import com.cmo2.auth.repository.AuthTokenRepository;
import com.cmo2.auth.repository.RoleRepository;
import com.cmo2.auth.repository.UserRepository;
import com.cmo2.auth.team.TeamDtos.InviteRequest;
import com.cmo2.auth.team.TeamDtos.MemberResponse;
import com.cmo2.shared.audit.AuditService;
import com.cmo2.shared.billing.PlanLimits;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.mail.MailService;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.shared.security.TokenHasher;
import com.cmo2.shared.tenancy.TenantGuard;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Team management: invite by email, change role, deactivate/reactivate. Seats are enforced
 * via {@link PlanLimits}. Authorization is at the service layer; role changes are OWNER-only
 * to prevent privilege escalation, and the last active OWNER can never be removed/disabled
 * (no-lockout invariant).
 */
@Service
public class TeamService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final AuthTokenRepository tokens;
    private final PlanLimits planLimits;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final AuditService audit;

    public TeamService(UserRepository users, RoleRepository roles, AuthTokenRepository tokens,
                       PlanLimits planLimits, PasswordEncoder passwordEncoder,
                       MailService mailService, AuditService audit) {
        this.users = users;
        this.roles = roles;
        this.tokens = tokens;
        this.planLimits = planLimits;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public List<MemberResponse> list() {
        Long tenantId = TenantContext.requireTenantId();
        return users.findByTenantIdOrderByCreatedAtAsc(tenantId).stream().map(MemberResponse::of).toList();
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public MemberResponse invite(InviteRequest req) {
        String email = req.email().trim().toLowerCase();
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("A user with that email already exists");
        }
        RoleName roleName = parseRole(req.role());
        if (roleName == RoleName.OWNER) {
            requireCallerIsOwner(); // only an OWNER may grant OWNER
        }
        planLimits.assertCanAddSeat();

        Role role = roles.findByName(roleName)
                .orElseThrow(() -> new IllegalStateException("Role " + roleName + " missing — check seed data"));

        User user = new User();
        user.setEmail(email);
        // Unusable random hash until the invitee sets a password via the accept-invite link.
        user.setPasswordHash(passwordEncoder.encode(TokenHasher.generateRawToken()));
        user.setFullName(req.fullName().trim());
        user.setStatus(UserStatus.PENDING_VERIFICATION);
        user.addRole(role);
        user = users.save(user);

        issueInvite(user);
        audit.record("TEAM_MEMBER_INVITED", "User", String.valueOf(user.getId()),
                email + " as " + roleName);
        return MemberResponse.of(user);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('OWNER')")
    public MemberResponse changeRole(Long userId, String roleRaw) {
        User user = findOwned(userId);
        RoleName roleName = parseRole(roleRaw);

        boolean wasOwner = hasRole(user, RoleName.OWNER);
        if (wasOwner && roleName != RoleName.OWNER && isLastOwner(user)) {
            throw ApiException.badRequest("You cannot remove the last owner. Promote someone else first.");
        }
        Role role = roles.findByName(roleName)
                .orElseThrow(() -> new IllegalStateException("Role " + roleName + " missing"));
        user.getRoles().clear();
        user.addRole(role);
        users.save(user);
        audit.record("TEAM_ROLE_CHANGED", "User", String.valueOf(user.getId()), "-> " + roleName);
        return MemberResponse.of(user);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public MemberResponse deactivate(Long userId) {
        User user = findOwned(userId);
        if (user.getId().equals(TenantContext.getUserId())) {
            throw ApiException.badRequest("You cannot deactivate your own account.");
        }
        if (hasRole(user, RoleName.OWNER) && isLastOwner(user)) {
            throw ApiException.badRequest("You cannot deactivate the last owner.");
        }
        user.setStatus(UserStatus.DISABLED);
        users.save(user);
        audit.record("TEAM_MEMBER_DEACTIVATED", "User", String.valueOf(user.getId()), user.getEmail());
        return MemberResponse.of(user);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public MemberResponse reactivate(Long userId) {
        User user = findOwned(userId);
        planLimits.assertCanAddSeat(); // re-enabling consumes a seat
        user.setStatus(user.isEmailVerified() ? UserStatus.ACTIVE : UserStatus.PENDING_VERIFICATION);
        users.save(user);
        audit.record("TEAM_MEMBER_REACTIVATED", "User", String.valueOf(user.getId()), user.getEmail());
        return MemberResponse.of(user);
    }

    // ---- helpers -------------------------------------------------------------

    private void issueInvite(User user) {
        String raw = TokenHasher.generateRawToken();
        AuthToken token = new AuthToken();
        token.setTenantId(user.getTenantId());
        token.setUserId(user.getId());
        token.setType(AuthTokenType.PASSWORD_RESET); // reused: invitee sets their initial password
        token.setTokenHash(TokenHasher.hash(raw));
        token.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        tokens.save(token);

        mailService.send(user.getEmail(), "You've been invited to CMO²",
                "You've been added to a CMO² workspace. Set your password to get started:\n\n"
                        + "http://localhost:5173/accept-invite?token=" + raw
                        + "\n\nThis invite expires in 7 days.");
    }

    private User findOwned(Long userId) {
        return TenantGuard.owned(users.findById(userId))
                .orElseThrow(() -> ApiException.notFound("Team member not found"));
    }

    private boolean hasRole(User user, RoleName role) {
        return user.getRoles().stream().anyMatch(r -> r.getName() == role);
    }

    private boolean isLastOwner(User user) {
        Long tenantId = TenantContext.requireTenantId();
        long owners = users.findByTenantIdOrderByCreatedAtAsc(tenantId).stream()
                .filter(u -> u.getStatus() != UserStatus.DISABLED)
                .filter(u -> hasRole(u, RoleName.OWNER))
                .count();
        return owners <= 1;
    }

    private void requireCallerIsOwner() {
        boolean isOwner = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_OWNER"));
        if (!isOwner) {
            throw ApiException.forbidden("Only an owner can grant the OWNER role.");
        }
    }

    private RoleName parseRole(String raw) {
        try {
            return RoleName.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            throw ApiException.badRequest("Unknown role: " + raw);
        }
    }
}
