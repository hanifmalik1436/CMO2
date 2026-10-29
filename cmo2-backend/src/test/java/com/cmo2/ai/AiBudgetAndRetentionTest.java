package com.cmo2.ai;

import com.cmo2.ai.domain.AiInteraction;
import com.cmo2.ai.repository.AiInteractionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Each customer has its own monthly AI ceiling, and old prompts are deleted after the retention period. */
@SpringBootTest(properties = "cmo2.ai.company-monthly-token-ceiling=1000000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiBudgetAndRetentionTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    AiInteractionRepository interactions;
    @Autowired
    AiInteractionRecorder recorder;

    private String register() throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"budget.%s@example.com","password":"superSecret123","fullName":"Owner","organizationName":"Agency"}
                        """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn();
        String token = json.readTree(res.getResponse().getContentAsString()).get("accessToken").asText();
        mvc.perform(post("/api/billing/simulate-activation").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PRO\"}")).andExpect(status().isOk());
        return token;
    }

    private long company(String token, String name) throws Exception {
        MvcResult res = mvc.perform(post("/api/companies").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private long tenantId(String token) throws Exception {
        MvcResult res = mvc.perform(get("/api/tenant/current").header("Authorization", "Bearer " + token)).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private int generate(String token, long companyId) throws Exception {
        return mvc.perform(post("/api/companies/" + companyId + "/assets").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"BLOG_POST\",\"brief\":\"x\"}"))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void aCustomerOverItsMonthlyBudgetIsStoppedWithoutBlockingTheOthers() throws Exception {
        String token = register();
        long acme = company(token, "Acme");
        long other = company(token, "Other");

        assertThat(generate(token, acme)).isEqualTo(201);  // well under the ceiling

        // Acme uses up its month (recorded like any other call).
        AiInteraction heavy = unsaved(Instant.now());
        heavy.setTenantId(tenantId(token));
        heavy.setCompanyId(acme);
        heavy.setInputTokens(1_000_000);
        interactions.save(heavy);

        assertThat(generate(token, acme)).isEqualTo(429);  // over: stopped
        assertThat(generate(token, other)).isEqualTo(201); // another customer is unaffected
    }

    @Test
    void promptsOlderThanTheRetentionPeriodAreDeleted() {
        AiInteraction old = row(Instant.now().minus(Duration.ofDays(400)));
        AiInteraction recent = row(Instant.now().minus(Duration.ofDays(5)));

        recorder.purgeExpired();

        assertThat(interactions.findById(old.getId())).isEmpty();
        assertThat(interactions.findById(recent.getId())).isPresent();
    }

    private AiInteraction row(Instant createdAt) {
        return interactions.save(unsaved(createdAt));
    }

    private static AiInteraction unsaved(Instant createdAt) {
        AiInteraction a = new AiInteraction();
        a.setTenantId(999_999L);
        a.setKind("TEST");
        a.setTier("GENERATION");
        a.setPromptText("lead details");
        a.setCreatedAt(createdAt);
        return a;
    }
}
