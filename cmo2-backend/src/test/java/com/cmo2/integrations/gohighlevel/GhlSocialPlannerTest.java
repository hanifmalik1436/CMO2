package com.cmo2.integrations.gohighlevel;

import com.cmo2.integrations.gohighlevel.GhlSocialPlanner.ScheduledPost;
import com.cmo2.integrations.gohighlevel.capability.Actor;
import com.cmo2.integrations.gohighlevel.capability.GhlAction.CreateSocialPost;
import com.cmo2.integrations.gohighlevel.capability.GhlAction.ListSocialAccounts;
import com.cmo2.integrations.gohighlevel.capability.GhlAction.ListUsers;
import com.cmo2.integrations.gohighlevel.capability.GhlActionExecutor;
import com.cmo2.shared.exceptions.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GhlSocialPlannerTest {

    private static final Instant AT = Instant.parse("2026-11-01T09:00:00Z");
    private static final String FB = "fbOauth_fbProfile123";
    private static final String IG = "igOauth_igProfile456";
    private static final String OLD = "liOauth_liProfile789";

    private final ObjectMapper json = new ObjectMapper();
    private final GhlActionExecutor executor = mock(GhlActionExecutor.class);
    private final GhlSocialPlanner planner = new GhlSocialPlanner(executor);

    private JsonNode node(String s) throws Exception {
        return json.readTree(s);
    }

    @BeforeEach
    void setUp() throws Exception {
        when(executor.execute(eq(7L), isA(ListSocialAccounts.class), eq(Actor.HUMAN), isNull())).thenReturn(node("""
                {"success":true,"results":{"accounts":[
                  {"id":"%s","name":"Acme FB","platform":"facebook"},
                  {"id":"%s","name":"Acme IG","platform":"instagram","isExpired":false},
                  {"id":"%s","name":"Acme LinkedIn","platform":"linkedin","isExpired":true}]}}"""
                .formatted(FB, IG, OLD)));
        when(executor.execute(eq(7L), isA(ListUsers.class), eq(Actor.HUMAN), isNull())).thenReturn(node("""
                {"users":[{"id":"aaaaaaaaaaaaaaaaaaaaaaaa","roles":{"role":"user"}},
                          {"id":"bbbbbbbbbbbbbbbbbbbbbbbb","roles":{"type":"account","role":"admin"}}]}"""));
    }

    @Test
    void readsTheConnectedAccounts() {
        assertThat(planner.accounts(7L)).extracting(GhlSocialPlanner.SocialAccount::platform)
                .containsExactly("facebook", "instagram", "linkedin");
        assertThat(planner.accounts(7L).get(2).expired()).isTrue();
    }

    @Test
    void schedulesOnEveryWorkingAccountAsTheSubAccountAdmin() throws Exception {
        when(executor.execute(eq(7L), isA(CreateSocialPost.class), eq(Actor.HUMAN), eq("asset:5"))).thenReturn(node("""
                {"success":true,"statusCode":201,"message":"Created Post",
                 "results":{"post":{"_id":"61bb16833b3f2791f9715be2","status":"scheduled"}}}"""));

        ScheduledPost post = planner.schedule(7L, "Hello", null, null, AT, "asset:5");

        assertThat(post.postId()).isEqualTo("61bb16833b3f2791f9715be2");
        assertThat(post.accountIds()).containsExactly(FB, IG);
        ArgumentCaptor<CreateSocialPost> sent = ArgumentCaptor.forClass(CreateSocialPost.class);
        verify(executor).execute(eq(7L), sent.capture(), eq(Actor.HUMAN), eq("asset:5"));
        assertThat(sent.getValue().userId()).isEqualTo("bbbbbbbbbbbbbbbbbbbbbbbb");
        assertThat(sent.getValue().scheduledAt()).isEqualTo(AT);
    }

    @Test
    void onlyAccountsTheCustomerConnectedCanBeTargeted() {
        assertThatThrownBy(() -> planner.schedule(7L, "Hello", List.of("someoneElsesAccount"), null, AT, "asset:5"))
                .isInstanceOf(ApiException.class).hasMessageContaining("isn't connected");
        assertThatThrownBy(() -> planner.schedule(7L, "Hello", List.of(OLD), null, AT, "asset:5"))
                .isInstanceOf(ApiException.class).hasMessageContaining("expired");
        verify(executor, never()).execute(eq(7L), isA(CreateSocialPost.class), any(), any());
    }

    @Test
    void anUnansweredCreateIsUnconfirmedButARefusalIsNot() {
        when(executor.execute(eq(7L), isA(CreateSocialPost.class), eq(Actor.HUMAN), eq("asset:5")))
                .thenThrow(new GhlApiException(0, "Could not reach GoHighLevel (POST /social-media-posting/loc1/posts): timeout"))
                .thenThrow(new GhlApiException(422, "GHL POST returned 422: userId is required"));

        assertThatThrownBy(() -> planner.schedule(7L, "Hello", List.of(FB), null, AT, "asset:5"))
                .isInstanceOf(GhlPostUnconfirmedException.class);
        assertThatThrownBy(() -> planner.schedule(7L, "Hello", List.of(FB), null, AT, "asset:5"))
                .isInstanceOf(GhlApiException.class)
                .isNotInstanceOf(GhlPostUnconfirmedException.class);
    }
}
