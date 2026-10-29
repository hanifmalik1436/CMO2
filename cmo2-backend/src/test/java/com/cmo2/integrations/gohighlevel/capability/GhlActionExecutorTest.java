package com.cmo2.integrations.gohighlevel.capability;

import com.cmo2.integrations.core.Platform;
import com.cmo2.integrations.core.PlatformCredentials;
import com.cmo2.integrations.domain.PlatformConnection;
import com.cmo2.integrations.gohighlevel.GhlGateway;
import com.cmo2.integrations.gohighlevel.capability.GhlAction.AddContactTags;
import com.cmo2.integrations.gohighlevel.capability.GhlAction.CreateSocialPost;
import com.cmo2.integrations.gohighlevel.capability.GhlAction.DeleteContact;
import com.cmo2.integrations.gohighlevel.capability.GhlAction.RemoveContactTags;
import com.cmo2.integrations.repository.PlatformConnectionRepository;
import com.cmo2.integrations.service.ConnectionService;
import com.cmo2.shared.audit.AuditService;
import com.cmo2.shared.exceptions.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GhlActionExecutorTest {

    private final PlatformConnectionRepository connectionRepo = mock(PlatformConnectionRepository.class);
    private final ConnectionService connectionService = mock(ConnectionService.class);
    private final GhlGateway gateway = mock(GhlGateway.class);
    private final AuditService audit = mock(AuditService.class);
    private GhlActionExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new GhlActionExecutor(new CapabilityGuard(), connectionRepo, connectionService, gateway, audit);
        PlatformConnection conn = new PlatformConnection();
        when(connectionRepo.findFirstByCompanyIdAndPlatform(7L, Platform.GOHIGHLEVEL)).thenReturn(Optional.of(conn));
        when(connectionService.credentials(conn))
                .thenReturn(PlatformCredentials.oauth("loc-token", "loc1", Instant.now().plusSeconds(3600)));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void reversibleWritesRunAutomaticallyAndAreAudited() {
        executor.execute(7L, new RemoveContactTags("c1", List.of("new-lead")), Actor.AGENT, null);

        verify(gateway).delete("/contacts/c1/tags", Map.of("tags", List.of("new-lead")), "loc-token", "loc1");
        verify(audit).record(eq("GHL_ACTION"), eq("Company"), eq("7"), contains("REMOVE_CONTACT_TAGS"));
    }

    @Test
    void addingTagsPostsToTheContact() {
        executor.execute(7L, new AddContactTags("c1", List.of("booked-call")), Actor.AGENT, null);

        verify(gateway).post("/contacts/c1/tags", Map.of("tags", List.of("booked-call")), "loc-token", "loc1");
    }

    private static final Instant AT = Instant.parse("2026-11-01T09:00:00Z");
    private static final String ACCOUNT = "aF3KhyL8JIuBwzK3m7Ly_iVrVJ2uoXNF0wzcBzgl5_12554616564525983496";
    private static final String USER = "64b1f0c2a1b2c3d4e5f60718";

    @Test
    void clientFacingActionsWithoutApprovalNeverReachGhl() {
        var post = new CreateSocialPost(List.of(ACCOUNT), "Hello", List.of(), AT, USER);

        assertThatThrownBy(() -> executor.execute(7L, post, Actor.AGENT, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(gateway, audit);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anApprovedPostIsScheduledInTheSocialPlanner() {
        var post = new CreateSocialPost(List.of(ACCOUNT), "Spring roof checks are open",
                List.of("https://cdn.test/roof.png?v=2"), AT, USER);

        executor.execute(7L, post, Actor.HUMAN, "asset:42");

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(gateway).post(eq("/social-media-posting/loc1/posts"), body.capture(), eq("loc-token"), eq("loc1"));
        Map<String, Object> sent = (Map<String, Object>) body.getValue();
        assertThat(sent).containsEntry("type", "post")
                .containsEntry("status", "scheduled")
                .containsEntry("accountIds", List.of(ACCOUNT))
                .containsEntry("summary", "Spring roof checks are open")
                .containsEntry("userId", USER)
                .containsEntry("scheduleDate", "2026-11-01T09:00:00Z")
                .containsEntry("media", List.of(Map.of("url", "https://cdn.test/roof.png?v=2", "type", "image/png")));
        verify(audit).record(eq("GHL_ACTION"), eq("Company"), eq("7"), contains("approval asset:42"));
    }

    @Test
    void agentsCanNeverRunDestructiveActions() {
        signInAs("ROLE_OWNER");

        assertThatThrownBy(() -> executor.execute(7L, new DeleteContact("c1"), Actor.AGENT, "anything"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(gateway);
    }

    @Test
    void destructiveActionsNeedASignedInOwnerOrAdmin() {
        signInAs("ROLE_ANALYST");

        assertThatThrownBy(() -> executor.execute(7L, new DeleteContact("c1"), Actor.HUMAN, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(gateway);
    }

    @Test
    void aCustomerWithNoGhlConnectionIsRejected() {
        when(connectionRepo.findFirstByCompanyIdAndPlatform(8L, Platform.GOHIGHLEVEL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> executor.execute(8L, new GhlAction.ReadLocation(), Actor.AGENT, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(gateway, org.mockito.Mockito.never()).get(anyString(), any(), anyString(), anyString());
    }

    @Test
    void actionParametersAreValidated() {
        assertThatThrownBy(() -> new AddContactTags("../contacts", List.of("x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AddContactTags("c1", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AddContactTags("c1", java.util.Collections.nCopies(21, "t")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CreateSocialPost(List.of(), "Hello", null, AT, USER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CreateSocialPost(List.of(ACCOUNT), "Hello", List.of("http://insecure.test/a.png"), AT, USER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CreateSocialPost(List.of(ACCOUNT), "Hello", null, null, USER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CreateSocialPost(List.of(ACCOUNT), "Hello", null, AT, "../users"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void signInAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user", null, List.of(new SimpleGrantedAuthority(role))));
    }
}
