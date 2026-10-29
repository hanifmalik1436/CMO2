package com.cmo2.docs;

import com.cmo2.docs.DocumentationService.DocResult;
import com.cmo2.docs.client.ConfluenceClient;
import com.cmo2.docs.client.DocClientException;
import com.cmo2.docs.client.LinearClient;
import com.cmo2.docs.client.LinearClient.LinearProject;
import com.cmo2.docs.client.LinearClient.LinearTeam;
import com.cmo2.docs.client.NotionClient;
import com.cmo2.docs.client.NotionClient.NotionPage;
import com.cmo2.docs.client.NotionClient.NotionParent;
import com.cmo2.docs.client.SlackClient;
import com.cmo2.integrations.events.ConnectionHealthChangedEvent;
import com.cmo2.integrations.events.CustomerEnabledEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 through the real API, with only the four tools' HTTP clients mocked: connect the
 * agency's tools, give each customer its own area, and prove a cycle writes only to its own.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkspaceToolsFlowTest {

    private static final String NOTION_TOKEN = "ntn_supersecrettoken9876";
    private static final String ROOT = "3f6da080000280f9934ec6ff2c2653ad";
    private static final String TEAM = "00517556-fe71-46b2-9bdb-34cde8451dd0";
    private static final String WEBHOOK = "https://hooks.slack.com/services/T000/B000/secretpart";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    DocumentationService documentation;
    @Autowired
    ApplicationEventPublisher events;
    @Autowired
    TransactionTemplate tx;

    @MockBean
    NotionClient notion;
    @MockBean
    ConfluenceClient confluence;
    @MockBean
    LinearClient linear;
    @MockBean
    SlackClient slack;

    @BeforeEach
    void stubTools() {
        when(notion.botName(NOTION_TOKEN)).thenReturn("CMO2 bot");
        when(notion.describeRoot(NOTION_TOKEN, ROOT)).thenReturn(new NotionParent(ROOT, "database", "Name", "https://notion.so/root"));
        when(notion.createPage(eq(NOTION_TOKEN), any(), anyString(), anyString())).thenAnswer(inv -> {
            String title = inv.getArgument(2);
            return new NotionPage("page-" + Math.abs(title.hashCode()), "https://notion.so/" + Math.abs(title.hashCode()));
        });
        when(linear.team("lin_api_key", TEAM)).thenReturn(new LinearTeam(TEAM, "CMO2", "CMO"));
        when(linear.createProject(eq("lin_api_key"), eq(TEAM), anyString(), anyString()))
                .thenAnswer(inv -> new LinearProject("proj-" + Math.abs(inv.getArgument(2).hashCode()), "https://linear.app/p"));
    }

    private String register() throws Exception {
        String email = "docs." + UUID.randomUUID() + "@example.com";
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

    private long createCompany(String token, String name) throws Exception {
        MvcResult res = mvc.perform(post("/api/companies").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private void connectNotion(String token) throws Exception {
        mvc.perform(put("/api/docs/connections/notion").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"secret\":\"" + NOTION_TOKEN + "\",\"notionRootId\":\"https://app.notion.com/p/" + ROOT + "?v=1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONNECTED"))
                .andExpect(jsonPath("$.maskedSecret").value("••••9876"))
                .andExpect(jsonPath("$.settings.rootKind").value("database"));
    }

    private void connectSlack(String token) throws Exception {
        mvc.perform(put("/api/docs/connections/slack").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"secret\":\"" + WEBHOOK + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONNECTED"));
    }

    @Test
    void secretsAreNeverReturned() throws Exception {
        String token = register();
        connectNotion(token);
        connectSlack(token);

        MvcResult list = mvc.perform(get("/api/docs/connections").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andReturn();
        String body = list.getResponse().getContentAsString();
        assertThat(body).doesNotContain(NOTION_TOKEN).doesNotContain("secretpart");
    }

    @Test
    void eachCustomerGetsItsOwnAreaAndACycleWritesOnlyThere() throws Exception {
        String token = register();
        long tenant = tenantId(token);
        connectNotion(token);
        connectSlack(token);
        mvc.perform(put("/api/docs/connections/linear").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"secret\":\"lin_api_key\",\"linearTeamId\":\"" + TEAM + "\"}"))
                .andExpect(jsonPath("$.status").value("CONNECTED"))
                .andExpect(jsonPath("$.settings.teamName").value("CMO2"));

        String suffix = UUID.randomUUID().toString().substring(0, 6);
        long alpha = createCompany(token, "Alpha " + suffix);
        long beta = createCompany(token, "Beta " + suffix);

        MvcResult a = mvc.perform(post("/api/companies/" + alpha + "/doc-targets/provision").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notionPageId").isNotEmpty())
                .andExpect(jsonPath("$.linearProjectId").isNotEmpty())
                .andReturn();
        MvcResult b = mvc.perform(post("/api/companies/" + beta + "/doc-targets/provision").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        String alphaPage = json.readTree(a.getResponse().getContentAsString()).get("notionPageId").asText();
        String betaPage = json.readTree(b.getResponse().getContentAsString()).get("notionPageId").asText();
        assertThat(alphaPage).isNotEqualTo(betaPage);

        // Provisioning again creates nothing new.
        mvc.perform(post("/api/companies/" + alpha + "/doc-targets/provision").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.notionPageId").value(alphaPage));

        DocResult result = documentation.publishCycle(tenant, alpha, 7, "Strange Loop cycle 7", "# Insights\n\nGood week.");

        assertThat(result.published()).isTrue();
        assertThat(result.targets()).containsExactly("notion");
        verify(notion).createPage(eq(NOTION_TOKEN), eq(NotionParent.page(alphaPage)), contains("cycle 7"), anyString());
        verify(notion, never()).createPage(eq(NOTION_TOKEN), eq(NotionParent.page(betaPage)), contains("cycle 7"), anyString());
        verify(slack).post(eq(WEBHOOK), argThat(text -> text.contains("Alpha " + suffix) && text.contains("cycle 7")));

        mvc.perform(get("/api/docs/targets").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void aBrokenToolIsSavedAsAnErrorWithTheReason() throws Exception {
        String token = register();
        when(notion.botName("ntn_badtoken")).thenThrow(new DocClientException("Notion rejected the token"));

        mvc.perform(put("/api/docs/connections/notion").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"secret\":\"ntn_badtoken\",\"notionRootId\":\"" + ROOT + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.lastError").value("Notion rejected the token"));
    }

    @Test
    void unsafeOrMalformedSettingsAreRefused() throws Exception {
        String token = register();
        mvc.perform(put("/api/docs/connections/slack").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"secret\":\"http://169.254.169.254/latest\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/docs/connections/confluence").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"secret":"tok","confluenceSiteUrl":"https://intranet.local","confluenceEmail":"me@acme.com","confluenceSpaceKey":"CMO"}
                                """))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/docs/connections/notion").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"notionRootId\":\"" + ROOT + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/docs/connections/dropbox").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void enablingACustomerSetsUpItsAreaAfterCommit() throws Exception {
        String token = register();
        long tenant = tenantId(token);
        connectNotion(token);
        long company = createCompany(token, "Gamma " + UUID.randomUUID().toString().substring(0, 6));

        tx.executeWithoutResult(s -> events.publishEvent(new CustomerEnabledEvent(tenant, company, "Gamma")));

        verify(notion).createPage(eq(NOTION_TOKEN), argThat(p -> p != null && "database".equals(p.kind())), startsWith("CMO² · Gamma"), anyString());
        mvc.perform(get("/api/docs/targets").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$[0].companyId").value(company))
                .andExpect(jsonPath("$[0].notionPageId").isNotEmpty());
    }

    @Test
    void aCustomerConnectionGoingDownIsPostedToSlack() throws Exception {
        String token = register();
        long tenant = tenantId(token);
        connectSlack(token);
        long company = createCompany(token, "Delta " + UUID.randomUUID().toString().substring(0, 6));

        events.publishEvent(new ConnectionHealthChangedEvent(tenant, company, "GoHighLevel", false, "token rejected"));

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(slack, org.mockito.Mockito.atLeastOnce()).post(eq(WEBHOOK), text.capture());
        assertThat(text.getAllValues()).anyMatch(t -> t.contains("Delta") && t.contains("connection is down") && t.contains("token rejected"));
    }

    @Test
    void tenantsNeverSeeEachOthersTools() throws Exception {
        String tokenA = register();
        String tokenB = register();
        connectNotion(tokenA);

        mvc.perform(get("/api/docs/connections").header("Authorization", "Bearer " + tokenB))
                .andExpect(jsonPath("$[?(@.provider=='NOTION')].configured").value(false));
        mvc.perform(post("/api/docs/connections/notion/test").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
        long companyA = createCompany(tokenA, "Epsilon " + UUID.randomUUID().toString().substring(0, 6));
        mvc.perform(post("/api/companies/" + companyA + "/doc-targets/provision").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }
}
