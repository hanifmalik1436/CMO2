package com.cmo2.assets;

import com.cmo2.assets.service.ApprovalService;
import com.cmo2.integrations.gohighlevel.GhlApiException;
import com.cmo2.integrations.gohighlevel.GhlPostUnconfirmedException;
import com.cmo2.integrations.gohighlevel.GhlSocialPlanner;
import com.cmo2.integrations.gohighlevel.GhlSocialPlanner.ScheduledPost;
import com.cmo2.integrations.gohighlevel.GhlSocialPlanner.SocialAccount;
import com.cmo2.integrations.gohighlevel.GoHighLevelConnector;
import com.cmo2.shared.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 through the real API: drafts wait for approval, only the owner or an admin can approve,
 * nothing unapproved can be published, and an approved social post is scheduled in GHL exactly
 * once even when GHL doesn't answer. GHL itself is replaced by a mocked {@link GhlSocialPlanner};
 * the connection is treated as live so the real publishing path runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApprovalFlowTest {

    private static final String FB = "fbOauth_fbProfile123";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    ApprovalService approvals;

    @MockBean
    GhlSocialPlanner planner;
    @SpyBean
    GoHighLevelConnector ghl;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private String register() throws Exception {
        String email = "approve." + UUID.randomUUID() + "@example.com";
        MvcResult res = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"%s","password":"superSecret123","fullName":"Owner","organizationName":"Agency"}
                        """.formatted(email)))
                .andExpect(status().isCreated()).andReturn();
        String token = json.readTree(res.getResponse().getContentAsString()).get("accessToken").asText();
        mvc.perform(post("/api/billing/simulate-activation").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PRO\"}")).andExpect(status().isOk());
        return token;
    }

    private long createCompany(String token) throws Exception {
        MvcResult res = mvc.perform(post("/api/companies").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Acme Roofing\"}"))
                .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(post("/api/companies/" + id + "/integrations").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"platform\":\"GOHIGHLEVEL\",\"apiKey\":\"demo-key\"}"))
                .andExpect(status().isCreated());
        return id;
    }

    /** Generates a social post; returns {english id, spanish id}. */
    private long[] draft(String token, long companyId) throws Exception {
        MvcResult res = mvc.perform(post("/api/companies/" + companyId + "/assets").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"SOCIAL_MEDIA\",\"brief\":\"Spring roof checks\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = json.readTree(res.getResponse().getContentAsString());
        return new long[] {body.get("english").get("id").asLong(), body.get("spanish").get("id").asLong()};
    }

    private MvcResult publish(String token, long assetId, String extra) throws Exception {
        return mvc.perform(post("/api/assets/" + assetId + "/deploy").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"platform\":\"GOHIGHLEVEL\"" + extra + "}")).andReturn();
    }

    @Test
    void draftsWaitInTheQueueUntilTheOwnerDecides() throws Exception {
        String token = register();
        long company = createCompany(token);
        long[] ids = draft(token, company);

        mvc.perform(get("/api/approvals").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].companyName").value("Acme Roofing"))
                .andExpect(jsonPath("$[0].asset.status").value("PENDING_APPROVAL"));

        mvc.perform(put("/api/assets/" + ids[0] + "/content").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Spring roof checks are open. Book a free survey.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(post("/api/assets/" + ids[1] + "/reject").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest()); // a rejection needs a reason
        mvc.perform(post("/api/assets/" + ids[1] + "/reject").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Too salesy\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reviewNote").value("Too salesy"))
                .andExpect(jsonPath("$.reviewedBy").isNumber());
        // A rejected draft can't be published or approved afterwards.
        assertThat(publish(token, ids[1], "").getResponse().getStatus()).isEqualTo(409);
        mvc.perform(post("/api/assets/" + ids[1] + "/approve").header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/approvals?status=REJECTED&companyId=" + company).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        verify(planner, never()).schedule(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void anApprovedPostIsScheduledInTheCustomersSocialPlanner() throws Exception {
        String token = register();
        long company = createCompany(token);
        long en = draft(token, company)[0];
        doReturn(true).when(ghl).isLive(any());
        Instant at = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        when(planner.schedule(eq(company), any(), eq(List.of(FB)), eq(List.of("https://cdn.test/roof.jpg")), eq(at), eq("asset:" + en)))
                .thenReturn(new ScheduledPost("61bb16833b3f2791f9715be2", at, List.of(FB)));

        assertThat(publish(token, en, "").getResponse().getStatus()).isEqualTo(409); // not approved yet
        mvc.perform(post("/api/assets/" + en + "/approve").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Good to go\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/assets/" + en + "/deploy").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"platform":"GOHIGHLEVEL","accountIds":["%s"],"scheduleAt":"%s",
                                 "mediaUrls":["https://cdn.test/roof.jpg"]}""".formatted(FB, at)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.externalRef").value("61bb16833b3f2791f9715be2"))
                .andExpect(jsonPath("$.targetAccounts[0]").value(FB));

        mvc.perform(get("/api/assets/" + en).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.status").value("DEPLOYED"));
        // Published once; a second publish is refused.
        assertThat(publish(token, en, "").getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void anUnansweredPublishNeedsAPersonToConfirmTheRetry() throws Exception {
        String token = register();
        long company = createCompany(token);
        long en = draft(token, company)[0];
        doReturn(true).when(ghl).isLive(any());
        mvc.perform(post("/api/assets/" + en + "/approve").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        when(planner.schedule(eq(company), any(), isNull(), isNull(), any(), eq("asset:" + en)))
                .thenThrow(new GhlPostUnconfirmedException(new GhlApiException(0, "Could not reach GoHighLevel")))
                .thenReturn(new ScheduledPost("p2", Instant.now().plusSeconds(300), List.of(FB)));

        mvc.perform(post("/api/assets/" + en + "/deploy").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"platform\":\"GOHIGHLEVEL\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("UNCONFIRMED"))
                .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.containsString("check the Social Planner")));
        mvc.perform(get("/api/assets/" + en).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // A plain retry is refused; a confirmed one goes ahead.
        assertThat(publish(token, en, "").getResponse().getStatus()).isEqualTo(409);
        mvc.perform(post("/api/assets/" + en + "/deploy").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"platform\":\"GOHIGHLEVEL\",\"confirmRetry\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        verify(planner, times(2)).schedule(eq(company), any(), isNull(), isNull(), any(), eq("asset:" + en));
    }

    @Test
    void aRefusedPostIsRecordedAndCanBeRetried() throws Exception {
        String token = register();
        long company = createCompany(token);
        long en = draft(token, company)[0];
        doReturn(true).when(ghl).isLive(any());
        mvc.perform(post("/api/assets/" + en + "/approve").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        when(planner.schedule(eq(company), any(), any(), any(), any(), any()))
                .thenThrow(new GhlApiException(422, "GHL POST returned 422: userId is required"));

        mvc.perform(post("/api/assets/" + en + "/deploy").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"platform\":\"GOHIGHLEVEL\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.containsString("refused")));
        mvc.perform(get("/api/assets/" + en + "/deployments").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(1));

        // A past schedule time is refused before GHL is called.
        mvc.perform(post("/api/assets/" + en + "/deploy").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"platform\":\"GOHIGHLEVEL\",\"scheduleAt\":\"2020-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theSocialAccountPickerShowsWhatTheCustomerConnected() throws Exception {
        String token = register();
        long company = createCompany(token);

        mvc.perform(get("/api/companies/" + company + "/social-accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.live").value(false));

        doReturn(true).when(ghl).isLive(any());
        when(planner.accounts(company)).thenReturn(List.of(new SocialAccount(FB, "Acme FB", "facebook", false)));
        mvc.perform(get("/api/companies/" + company + "/social-accounts").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.live").value(true))
                .andExpect(jsonPath("$.accounts[0].platform").value("facebook"));
    }

    @Test
    void onlyTheOwnerOrAnAdminCanApproveAndOtherAgenciesCantTouchIt() throws Exception {
        String ownerA = register();
        long company = createCompany(ownerA);
        long en = draft(ownerA, company)[0];
        String ownerB = register();

        mvc.perform(post("/api/assets/" + en + "/approve").header("Authorization", "Bearer " + ownerB))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/approvals?companyId=" + company).header("Authorization", "Bearer " + ownerB))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/approvals").header("Authorization", "Bearer " + ownerB))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/companies/" + company + "/social-accounts").header("Authorization", "Bearer " + ownerB))
                .andExpect(status().isNotFound());

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "strategist", null, List.of(new SimpleGrantedAuthority("ROLE_STRATEGIST"))));
        assertThatThrownBy(() -> approvals.approve(en, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> approvals.queue(null, null)).isInstanceOf(AccessDeniedException.class);
    }
}
