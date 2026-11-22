package com.cmo2.customers;

import com.cmo2.customers.dto.CustomerDtos.SaveCanonDraftRequest;
import com.cmo2.customers.service.CustomerCanonService;
import com.cmo2.customers.service.CustomerIdentityService;
import com.cmo2.shared.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The brand canon (drift-control plan, step 1) through the real API and database: only the
 * approved version reaches the AI, approving retires the previous one, and the canon stays
 * inside its own agency.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerCanonFlowTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    CustomerCanonService canons;
    @Autowired
    CustomerIdentityService identity;

    @AfterEach
    void clear() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private String register() throws Exception {
        String email = "canon." + UUID.randomUUID() + "@example.com";
        MvcResult res = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"%s","password":"superSecret123","fullName":"Owner","organizationName":"Agency"}
                        """.formatted(email)))
                .andExpect(status().isCreated()).andReturn();
        String token = json.readTree(res.getResponse().getContentAsString()).get("accessToken").asText();
        mvc.perform(post("/api/billing/simulate-activation").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PRO\"}")).andExpect(status().isOk());
        return token;
    }

    private long tenantId(String token) throws Exception {
        MvcResult res = mvc.perform(get("/api/tenant/current").header("Authorization", "Bearer " + token)).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private long createCompany(String token) throws Exception {
        MvcResult res = mvc.perform(post("/api/companies").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Texas Property Tax Advocates\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private ResultActions saveDraft(String token, long company, String content) throws Exception {
        return mvc.perform(put("/api/companies/" + company + "/canon/draft").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new SaveCanonDraftRequest(content, "first version"))));
    }

    private JsonNode approve(String token, long company) throws Exception {
        MvcResult res = mvc.perform(post("/api/companies/" + company + "/canon/draft/approve")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(res.getResponse().getContentAsString());
    }

    private String aiIsTold(String token, long company) throws Exception {
        MvcResult res = mvc.perform(get("/api/companies/" + company + "/identity").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("text").asText();
    }

    @Test
    void onlyTheApprovedVersionReachesTheAiAndApprovingRetiresThePreviousOne() throws Exception {
        String token = register();
        long company = createCompany(token);

        saveDraft(token, company, "Voice: private wealth manager.\nNever say \"we buy houses\".")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft.status").value("DRAFT"))
                .andExpect(jsonPath("$.draft.versionNo").doesNotExist())
                .andExpect(jsonPath("$.approved").doesNotExist());
        // A draft is not what the AI gets.
        assertThat(aiIsTold(token, company)).doesNotContain("APPROVED BRAND CANON").doesNotContain("we buy houses");

        JsonNode v1 = approve(token, company);
        assertThat(v1.get("approved").get("versionNo").asInt()).isEqualTo(1);
        assertThat(v1.get("draft").isNull()).isTrue();
        assertThat(aiIsTold(token, company))
                .contains("APPROVED BRAND CANON (version 1")
                .contains("  Voice: private wealth manager.\n  Never say \"we buy houses\".");

        // Editing again opens a new draft; the AI keeps version 1 until the draft is approved.
        saveDraft(token, company, "Voice: calm, dignified.\nTwo lanes: Estate and Judgment.").andExpect(status().isOk());
        assertThat(aiIsTold(token, company)).contains("version 1").doesNotContain("Two lanes");

        JsonNode v2 = approve(token, company);
        assertThat(v2.get("approved").get("versionNo").asInt()).isEqualTo(2);
        assertThat(v2.get("history")).hasSize(2);
        assertThat(v2.get("history").get(1).get("status").asText()).isEqualTo("RETIRED");
        assertThat(aiIsTold(token, company)).contains("version 2").contains("Two lanes").doesNotContain("wealth manager");

        // A discarded draft leaves the approved version alone.
        saveDraft(token, company, "Something the owner changed their mind about").andExpect(status().isOk());
        mvc.perform(delete("/api/companies/" + company + "/canon/draft").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft").doesNotExist())
                .andExpect(jsonPath("$.approved.versionNo").value(2));
    }

    @Test
    void anEmptyDraftOrApprovingNothingIsRefused() throws Exception {
        String token = register();
        long company = createCompany(token);

        saveDraft(token, company, "   ").andExpect(status().isBadRequest());
        mvc.perform(post("/api/companies/" + company + "/canon/draft/approve").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anotherAgencyCannotSeeOrChangeTheCanon() throws Exception {
        String ownerA = register();
        long company = createCompany(ownerA);
        saveDraft(ownerA, company, "Private brand rules").andExpect(status().isOk());
        approve(ownerA, company);
        String ownerB = register();

        mvc.perform(get("/api/companies/" + company + "/canon").header("Authorization", "Bearer " + ownerB))
                .andExpect(status().isNotFound());
        saveDraft(ownerB, company, "Hijacked").andExpect(status().isNotFound());
        mvc.perform(post("/api/companies/" + company + "/canon/draft/approve").header("Authorization", "Bearer " + ownerB))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/companies/" + company + "/canon/draft").header("Authorization", "Bearer " + ownerB))
                .andExpect(status().isNotFound());

        // Internal callers name the tenant; the wrong one gets nothing.
        assertThat(canons.approved(tenantId(ownerB), company)).isEmpty();
        assertThat(identity.identityBlock(tenantId(ownerB), company)).isEmpty();
        assertThat(aiIsTold(ownerA, company)).contains("Private brand rules");
    }

    @Test
    void aStrategistCanDraftButOnlyTheOwnerOrAnAdminCanApprove() throws Exception {
        String token = register();
        long tenant = tenantId(token);
        long company = createCompany(token);

        TenantContext.set(tenant, null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "strategist", null, List.of(new SimpleGrantedAuthority("ROLE_STRATEGIST"))));
        assertThat(canons.saveDraft(company, new SaveCanonDraftRequest("Drafted by staff", null)).draft()).isNotNull();
        assertThatThrownBy(() -> canons.approveDraft(company)).isInstanceOf(AccessDeniedException.class);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "viewer", null, List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))));
        assertThatThrownBy(() -> canons.saveDraft(company, new SaveCanonDraftRequest("x", null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> canons.get(company)).isInstanceOf(AccessDeniedException.class);
    }
}
