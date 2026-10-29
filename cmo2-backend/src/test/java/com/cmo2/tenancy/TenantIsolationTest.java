package com.cmo2.tenancy;

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
 * The tenant-leak suite: authenticate as Tenant A, then prove Tenant A can neither
 * list nor fetch Tenant B's data. This is the CI-blocking guarantee behind
 * NON-NEGOTIABLE RULE #1 (tenant isolation). Every new endpoint must keep it green.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantIsolationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    private String register(String email, String org) throws Exception {
        String body = """
                {"email":"%s","password":"superSecret123","fullName":"Test User","organizationName":"%s"}
                """.formatted(email, org);
        MvcResult res = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private long createCompany(String token, String name) throws Exception {
        MvcResult res = mvc.perform(post("/api/companies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void tenantsCannotSeeEachOthersCompanies() throws Exception {
        String tokenA = register("owner.a@example.com", "Agency A");
        String tokenB = register("owner.b@example.com", "Agency B");

        createCompany(tokenA, "Acme");
        long globexId = createCompany(tokenB, "Globex");

        // A lists -> only Acme, never Globex.
        mvc.perform(get("/api/companies").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Acme"));

        // A directly requests B's company by id -> 404 (filtered out, not merely forbidden).
        mvc.perform(get("/api/companies/" + globexId).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());

        // B sees only its own.
        MvcResult bList = mvc.perform(get("/api/companies").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode arr = json.readTree(bList.getResponse().getContentAsString());
        assertThat(arr).hasSize(1);
        assertThat(arr.get(0).get("name").asText()).isEqualTo("Globex");
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/companies")).andExpect(status().isUnauthorized());
    }
}
