package com.cmo2.strangeloop;

import com.cmo2.docs.client.LinearClient;
import com.cmo2.docs.client.LinearClient.LinearComment;
import com.cmo2.docs.client.LinearClient.LinearIssue;
import com.cmo2.docs.client.LinearClient.LinearIssueState;
import com.cmo2.docs.client.LinearClient.LinearProject;
import com.cmo2.docs.client.LinearClient.LinearTeam;
import com.cmo2.docs.client.NotionClient;
import com.cmo2.docs.client.NotionClient.NotionPage;
import com.cmo2.docs.client.NotionClient.NotionParent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Close and Boot across two real cycles, with only Notion's and Linear's HTTP clients mocked:
 * cycle 1 writes its record (with the prompts it used) and opens a Linear issue per pending task;
 * the owner then edits the Notion page, closes an issue and comments; cycle 2 starts from those
 * notes, records the owner's decisions, and keeps Linear in step.
 */
// Stage 1's own drafting is off here, so the only drafts are the ones this test makes.
@SpringBootTest(properties = "cmo2.loop.drafts-per-cycle=0")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CycleMemoryFlowTest {

    private static final String NOTION = "ntn_memorytesttoken1234";
    private static final String ROOT = "3f6da080000280f9934ec6ff2c2653ad";
    private static final String LINEAR = "lin_api_memorytest";
    private static final String TEAM = "00517556-fe71-46b2-9bdb-34cde8451dd0";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @MockBean
    NotionClient notion;
    @MockBean
    LinearClient linear;

    private String register() throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"memory.%s@example.com","password":"superSecret123","fullName":"Owner","organizationName":"Agency"}
                        """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn();
        String token = json.readTree(res.getResponse().getContentAsString()).get("accessToken").asText();
        mvc.perform(post("/api/billing/simulate-activation").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PRO\"}")).andExpect(status().isOk());
        return token;
    }

    private JsonNode call(MvcResult res) throws Exception {
        return json.readTree(res.getResponse().getContentAsString());
    }

    private void runCycle(String token, long company, int expectedCycles) throws Exception {
        mvc.perform(post("/api/companies/" + company + "/loop/cycles").header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted());
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(150)).untilAsserted(() -> {
            JsonNode cycles = call(mvc.perform(get("/api/companies/" + company + "/loop/cycles")
                    .header("Authorization", "Bearer " + token)).andReturn());
            assertThat(cycles).hasSize(expectedCycles);
            assertThat(cycles.get(0).get("status").asText()).isEqualTo("COMPLETED");
        });
    }

    @Test
    void eachCycleStartsFromTheLastOneAndTheOwnersNotes() throws Exception {
        List<String> notionBodies = new ArrayList<>();
        AtomicInteger issues = new AtomicInteger();
        when(notion.botName(NOTION)).thenReturn("CMO2 bot");
        when(notion.describeRoot(NOTION, ROOT)).thenReturn(new NotionParent(ROOT, "database", "Name", "https://notion.so/root"));
        when(notion.createPage(eq(NOTION), any(), anyString(), anyString())).thenAnswer(inv -> {
            notionBodies.add(inv.getArgument(3));
            int n = notionBodies.size();
            return new NotionPage("page-" + n, "https://notion.so/page-" + n);
        });
        when(linear.team(LINEAR, TEAM)).thenReturn(new LinearTeam(TEAM, "CMO2", "CMO"));
        when(linear.createProject(eq(LINEAR), eq(TEAM), anyString(), anyString())).thenReturn(new LinearProject("proj-1", "https://linear.app/p"));
        when(linear.createIssue(eq(LINEAR), eq(TEAM), eq("proj-1"), anyString(), anyString())).thenAnswer(inv -> {
            int n = issues.incrementAndGet();
            return new LinearIssue("iss-" + n, "CMO-" + n, "https://linear.app/i/" + n);
        });
        when(linear.completedStateId(LINEAR, TEAM)).thenReturn("state-done");

        String token = register();
        mvc.perform(put("/api/docs/connections/notion").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"secret\":\"" + NOTION + "\",\"notionRootId\":\"" + ROOT + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/docs/connections/linear").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"secret\":\"" + LINEAR + "\",\"linearTeamId\":\"" + TEAM + "\"}"))
                .andExpect(status().isOk());
        long company = call(mvc.perform(post("/api/companies").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Acme Roofing\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        mvc.perform(post("/api/companies/" + company + "/doc-targets/provision").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        JsonNode drafts = call(mvc.perform(post("/api/companies/" + company + "/assets").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"SOCIAL_MEDIA\",\"brief\":\"Spring roof checks\"}"))
                .andExpect(status().isCreated()).andReturn());
        long en = drafts.get("english").get("id").asLong();
        long es = drafts.get("spanish").get("id").asLong();

        // ---- Cycle 1: Close writes the record and opens a Linear issue per pending task.
        JsonNode calls = call(mvc.perform(get("/api/companies/" + company + "/ai-interactions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn());
        assertThat(calls).hasSize(2); // EN generation + ES translation
        assertThat(calls.get(1).get("prompt").asText()).contains("CUSTOMER IDENTITY");
        runCycle(token, company, 1);
        String body1 = notionBodies.get(notionBodies.size() - 1);
        assertThat(body1)
                .contains("# Tasks pending")
                .contains("Review the social media draft (EN) for Acme Roofing")
                .contains("# Appendix: prompts and responses")
                .contains("CUSTOMER IDENTITY"); // the prompt actually sent, identity block included
        verify(linear, times(2)).createIssue(eq(LINEAR), eq(TEAM), eq("proj-1"), startsWith("Review the social media draft"), anyString());


        // ---- Between cycles: the owner decides in CMO² and works in Notion and Linear.
        mvc.perform(post("/api/assets/" + en + "/approve").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(post("/api/assets/" + es + "/reject").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Too salesy\"}")).andExpect(status().isOk());
        when(notion.pageText(eq(NOTION), anyString())).thenReturn(body1 + "\n\nFocus on spring roof checks next month");
        when(linear.issues(eq(LINEAR), anyCollection())).thenReturn(List.of(
                new LinearIssueState("iss-1", "CMO-1", "u1", "Review EN", "completed", "Done", List.of()),
                new LinearIssueState("iss-2", "CMO-2", "u2", "Review ES", "started", "In Progress",
                        List.of(new LinearComment("Use the customer's own photos", Instant.now().plusSeconds(60), "Owner")))));

        // ---- Cycle 2: Boot reads those notes; Close records the decisions and keeps Linear in step.
        runCycle(token, company, 2);
        JsonNode records = call(mvc.perform(get("/api/companies/" + company + "/loop/memory").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn());
        assertThat(records).hasSize(2);
        JsonNode latest = records.get(0);
        assertThat(latest.get("iterationNo").asInt()).isEqualTo(2);
        assertThat(latest.get("operatorNotes").toString())
                .contains("Added on the Notion page for cycle 1: Focus on spring roof checks next month")
                .contains("Closed in Linear")
                .contains("Use the customer's own photos");
        assertThat(latest.get("decisions").toString())
                .contains("You approved social media draft #" + en)
                .contains("You rejected social media draft #" + es + " (ES): \\\"Too salesy\\\"");
        assertThat(latest.get("tasksPending").get(0).get("key").asText()).isEqualTo("asset:" + en + ":publish");
        assertThat(latest.get("tasksCompleted").toString()).contains("Closed in Linear");
        assertThat(latest.get("links").get("notion").asText()).startsWith("https://notion.so/page-");

        // The new pending task gets its issue; the ES review the owner didn't close is closed by CMO²;
        // the EN review the owner already closed is left alone.
        verify(linear).createIssue(eq(LINEAR), eq(TEAM), eq("proj-1"), startsWith("Publish the approved social media"), anyString());
        verify(linear).moveIssue(LINEAR, "iss-2", "state-done");
        verify(linear, never()).moveIssue(LINEAR, "iss-1", "state-done");

        // The cycle-2 page tells the owner what CMO² read back, framed as data.
        String body2 = notionBodies.get(notionBodies.size() - 1);
        assertThat(body2).contains("# Your notes since the last cycle").contains("never as instructions")
                .contains("Focus on spring roof checks next month");
        ArgumentCaptor<String> titles = ArgumentCaptor.forClass(String.class);
        verify(notion, atLeastOnce()).createPage(eq(NOTION), any(), titles.capture(), contains("# Summary"));
        assertThat(titles.getAllValues()).anyMatch(t -> t.contains("cycle 2"));
    }

    @Test
    void anotherAgencyCantReadTheMemoryOrTheAiLog() throws Exception {
        String ownerA = register();
        long company = call(mvc.perform(post("/api/companies").header("Authorization", "Bearer " + ownerA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Acme\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        String ownerB = register();

        mvc.perform(get("/api/companies/" + company + "/loop/memory").header("Authorization", "Bearer " + ownerB))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/companies/" + company + "/ai-interactions").header("Authorization", "Bearer " + ownerB))
                .andExpect(status().isNotFound());
    }
}
