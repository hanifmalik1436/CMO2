package com.cmo2.assets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 exit criterion: generate a 7-email sequence, get both language versions,
 * deploy to GoHighLevel from the API, with token usage metered. Runs on the stub
 * AI provider (test profile) so it needs no API key.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssetGenerationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    private String register(String email) throws Exception {
        String body = """
                {"email":"%s","password":"superSecret123","fullName":"Asset User","organizationName":"Asset Org"}
                """.formatted(email);
        MvcResult res = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private long createCompany(String token, String name) throws Exception {
        MvcResult res = mvc.perform(post("/api/companies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void generateTranslateAndDeploy() throws Exception {
        String token = register("asset.owner@example.com");
        long companyId = createCompany(token, "Acme");

        // Generate EN + auto-translated ES.
        MvcResult gen = mvc.perform(post("/api/companies/" + companyId + "/assets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"EMAIL_SEQUENCE\",\"brief\":\"Welcome new leads\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.english.language").value("EN"))
                .andExpect(jsonPath("$.english.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.spanish.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.spanish.language").value("ES"))
                .andReturn();
        JsonNode body = json.readTree(gen.getResponse().getContentAsString());
        long englishId = body.get("english").get("id").asLong();
        assertThat(body.get("english").get("tokensUsed").asInt()).isGreaterThan(0);
        assertThat(body.get("english").get("contentJson").asText()).contains("Email 7");

        // Token usage is metered and visible.
        mvc.perform(get("/api/usage/current").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokensUsed").isNumber());
        MvcResult usage = mvc.perform(get("/api/usage/current").header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(json.readTree(usage.getResponse().getContentAsString()).get("tokensUsed").asLong())
                .isGreaterThan(0);

        // Connect GoHighLevel (demo mode) before deploying — Deployer requires a live
        // connection to source credentials from, same as a manual KPI sync does.
        mvc.perform(post("/api/companies/" + companyId + "/integrations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"GOHIGHLEVEL\",\"apiKey\":\"demo-key\"}"))
                .andExpect(status().isCreated());

        // Nothing leaves CMO² before it is approved.
        mvc.perform(post("/api/assets/" + englishId + "/deploy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"GOHIGHLEVEL\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/assets/" + englishId + "/approve").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // Deploy the approved English asset to GoHighLevel (demo connector accepts it).
        mvc.perform(post("/api/assets/" + englishId + "/deploy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"GOHIGHLEVEL\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.externalRef").isNotEmpty());
    }

    @Test
    void tenantsCannotReadEachOthersAssets() throws Exception {
        String tokenA = register("asset.a@example.com");
        String tokenB = register("asset.b@example.com");
        long companyA = createCompany(tokenA, "A Co");

        MvcResult gen = mvc.perform(post("/api/companies/" + companyA + "/assets")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"BLOG_POST\",\"brief\":\"x\"}"))
                .andExpect(status().isCreated()).andReturn();
        long assetA = json.readTree(gen.getResponse().getContentAsString()).get("english").get("id").asLong();

        mvc.perform(get("/api/assets/" + assetA).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }
}
