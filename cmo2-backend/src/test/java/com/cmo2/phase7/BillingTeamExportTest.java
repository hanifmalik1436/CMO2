package com.cmo2.phase7;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 7 — the "sellable" gate. Proves, with the stub billing gateway (no Stripe account):
 * <ul>
 *   <li>A new tenant starts on STARTER/TRIAL; demo checkout + activation drives it ACTIVE on PRO.</li>
 *   <li>Stripe-style webhooks drive active → past_due → canceled.</li>
 *   <li>Plan limits are enforced server-side with a 402 upgrade prompt, and lift after upgrade.</li>
 *   <li>Team seats are enforced; invite/deactivate/reactivate and role changes work.</li>
 *   <li>A tenant can export its own data; tenants stay isolated.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BillingTeamExportTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    private String register(String email) throws Exception {
        String body = """
                {"email":"%s","password":"superSecret123","fullName":"P7 User","organizationName":"P7 Org"}
                """.formatted(email);
        MvcResult res = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private long tenantId(String token) throws Exception {
        MvcResult res = mvc.perform(get("/api/tenant/current").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private void createCompany(String token, String name, int expectedStatus) throws Exception {
        mvc.perform(post("/api/companies").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    private void simulate(String token, String plan) throws Exception {
        mvc.perform(post("/api/billing/simulate-activation").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"" + plan + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void demoCheckoutAndWebhookLifecycle() throws Exception {
        String token = register("p7.billing@example.com");
        long tid = tenantId(token);

        // New tenant: STARTER on trial, demo gateway.
        mvc.perform(get("/api/billing/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("STARTER"))
                .andExpect(jsonPath("$.status").value("TRIAL"))
                .andExpect(jsonPath("$.demo").value(true))
                .andExpect(jsonPath("$.usage.length()").value(4))
                .andExpect(jsonPath("$.plans.length()").value(3));

        // Checkout returns a (demo) redirect URL.
        mvc.perform(post("/api/billing/checkout").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PRO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demo").value(true))
                .andExpect(jsonPath("$.url").isNotEmpty());

        // Complete the demo payment -> ACTIVE on PRO with a paid period.
        simulate(token, "PRO");
        mvc.perform(get("/api/billing/summary").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.plan").value("PRO"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.currentPeriodEnd").isNotEmpty())
                .andExpect(jsonPath("$.hasPaymentMethod").value(true));

        // Stripe webhook: payment failed -> PAST_DUE (resolved by customer id).
        mvc.perform(post("/api/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"invoice.payment_failed\",\"customerId\":\"cus_demo_" + tid + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/billing/summary").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.status").value("PAST_DUE"));

        // Stripe webhook: subscription deleted -> CANCELED (resolved by subscription id).
        mvc.perform(post("/api/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"customer.subscription.deleted\",\"subscriptionId\":\"sub_demo_" + tid + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/billing/summary").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.status").value("CANCELED"));
    }

    @Test
    void planLimitsEnforcedAndLiftAfterUpgrade() throws Exception {
        String token = register("p7.limits@example.com");

        // STARTER allows exactly 1 company; the 2nd is refused with a 402 upgrade prompt.
        createCompany(token, "First Co", 201);
        MvcResult denied = mvc.perform(post("/api/companies").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Second Co\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.details.code").value("PLAN_LIMIT"))
                .andExpect(jsonPath("$.details.limitType").value("companies"))
                .andExpect(jsonPath("$.details.suggestedPlan").value("Pro"))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(402);

        // Upgrade to PRO -> the same create now succeeds.
        simulate(token, "PRO");
        createCompany(token, "Second Co", 201);
    }

    @Test
    void teamSeatsInviteDeactivateAndRoles() throws Exception {
        String token = register("p7.team@example.com");

        // Owner is the first (and only) member.
        mvc.perform(get("/api/team").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].roles[0]").value("OWNER"));

        // STARTER = 2 seats. Owner is seat 1; invite #1 fits.
        MvcResult invite1 = mvc.perform(post("/api/team/invite").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"analyst@example.com\",\"fullName\":\"Ana Lyst\",\"role\":\"ANALYST\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
                .andReturn();
        long memberId = json.readTree(invite1.getResponse().getContentAsString()).get("id").asLong();

        // Invite #2 would be seat 3 -> blocked with a seats 402.
        mvc.perform(post("/api/team/invite").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"viewer@example.com\",\"fullName\":\"Vi Ewer\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.details.limitType").value("seats"));

        // Deactivate frees a seat; now the invite fits.
        mvc.perform(post("/api/team/" + memberId + "/deactivate").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        mvc.perform(post("/api/team/invite").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"viewer@example.com\",\"fullName\":\"Vi Ewer\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated());

        // Owner can change a member's role.
        mvc.perform(patch("/api/team/" + memberId + "/role").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"STRATEGIST\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("STRATEGIST"));
    }

    @Test
    void dataExportAndTenantIsolation() throws Exception {
        String tokenA = register("p7.export.a@example.com");
        String tokenB = register("p7.export.b@example.com");
        createCompany(tokenA, "Export Co", 201);
        simulate(tokenA, "PRO");

        // A exports its own bundle.
        MvcResult export = mvc.perform(get("/api/tenant/export").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.plan").value("PRO"))
                .andExpect(jsonPath("$.team.length()").value(1))
                .andExpect(jsonPath("$.companies.length()").value(1))
                .andReturn();
        assertThat(export.getResponse().getHeader("Content-Disposition")).contains("attachment");

        // B is unaffected by A's upgrade, and sees only its own (empty) world.
        mvc.perform(get("/api/billing/summary").header("Authorization", "Bearer " + tokenB))
                .andExpect(jsonPath("$.plan").value("STARTER"))
                .andExpect(jsonPath("$.status").value("TRIAL"));
        mvc.perform(get("/api/tenant/export").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companies.length()").value(0))
                .andExpect(jsonPath("$.team.length()").value(1));
    }
}
