package com.cmo2.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the app with the {@code demo} profile active (on top of {@code test}'s H2) and proves the
 * {@link com.cmo2.shared.demo.DemoDataSeeder} produced a real, queryable, populated tenant — the
 * same data a client demo opens into. Guards the demo before it's shown live.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "demo"})
// This is a SECOND Spring context (the demo profile). Give it its own in-memory database so its
// create-drop schema lifecycle can't collide with the shared `cmo2test` DB the other tests use.
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:cmo2demo;MODE=MySQL;DB_CLOSE_DELAY=-1")
class DemoSeedTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    private String loginAsOwner() throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@demo.cmo2\",\"password\":\"demo1234\"}"))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    void demoTenantIsFullyPopulated() throws Exception {
        String token = loginAsOwner();

        // Three companies were seeded.
        MvcResult companies = mvc.perform(get("/api/companies").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andReturn();
        long companyId = json.readTree(companies.getResponse().getContentAsString()).get(0).get("id").asLong();

        // KPIs were synced for that company (connectors ran in demo mode).
        mvc.perform(get("/api/companies/" + companyId + "/kpis/latest").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        // The alert center has at least the simulated CPL spike.
        mvc.perform(get("/api/alerts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        // At least one leadership report was generated.
        mvc.perform(get("/api/reports").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    void everyRoleCanSignIn() throws Exception {
        for (String email : new String[] {
                "owner@demo.cmo2", "admin@demo.cmo2", "strategist@demo.cmo2",
                "analyst@demo.cmo2", "viewer@demo.cmo2" }) {
            mvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + email + "\",\"password\":\"demo1234\"}"))
                    .andExpect(status().isOk());
        }
    }
}
