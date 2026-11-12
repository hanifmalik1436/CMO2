package com.cmo2.studio;

import com.cmo2.assets.domain.AssetType;
import com.cmo2.assets.domain.ContentFormat;
import com.cmo2.studio.dto.StudioDtos.BatchRequest;
import com.cmo2.studio.service.StudioService;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Content Studio end to end on the demo AI provider: a transcript goes in, ideas come out,
 * a batch writes one draft per idea × format, every draft waits in Approvals with its platform and
 * format, and only single-post formats can be published to GoHighLevel.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContentStudioFlowTest {

    static final String TRANSCRIPT = """
            Most homeowners don't say no to solar, they just stop replying after the quote arrives.
            The quote shows a price but not the monthly saving, so it feels like a cost rather than an investment.
            Calling back within an hour of an enquiry usually means you are the only installer they talk to.
            A two-minute video walking through the roof plan builds far more trust than a PDF ever will.
            The best day to ask for a review is the day the panels switch on and the customer is excited.
            """;

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    StudioService studio;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    String register() throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"studio.%s@example.com","password":"superSecret123","fullName":"Owner","organizationName":"Agency"}
                        """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn();
        String token = read(res).get("accessToken").asText();
        mvc.perform(post("/api/billing/simulate-activation").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PRO\"}")).andExpect(status().isOk());
        return token;
    }

    long customer(String token) throws Exception {
        long id = read(mvc.perform(post("/api/companies").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Northwind Solar\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        mvc.perform(post("/api/companies/" + id + "/integrations").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"platform\":\"GOHIGHLEVEL\",\"apiKey\":\"demo-key\"}"))
                .andExpect(status().isCreated());
        return id;
    }

    long source(String token, long company) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("title", "Podcast: why solar quotes stall",
                "kind", "TRANSCRIPT", "body", TRANSCRIPT));
        return read(mvc.perform(post("/api/companies/" + company + "/studio/sources").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn()).get("source").get("id").asLong();
    }

    JsonNode analyze(String token, long source, int count) throws Exception {
        return read(mvc.perform(post("/api/studio/sources/" + source + "/analyze?count=" + count)
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn());
    }

    MvcResult batch(String token, long company, String body) throws Exception {
        return mvc.perform(post("/api/companies/" + company + "/studio/batches").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    JsonNode awaitBatch(String token, long batchId) {
        JsonNode[] last = new JsonNode[1];
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(150)).untilAsserted(() -> {
            last[0] = read(mvc.perform(get("/api/studio/batches/" + batchId).header("Authorization", "Bearer " + token)).andReturn());
            assertThat(last[0].get("status").asText()).isNotEqualTo("RUNNING");
        });
        return last[0];
    }

    JsonNode read(MvcResult res) throws Exception {
        return json.readTree(res.getResponse().getContentAsString());
    }

    @Test
    void transcriptToIdeasToDraftsForEachPlatform() throws Exception {
        String token = register();
        long company = customer(token);
        long source = source(token, company);

        JsonNode detail = analyze(token, source, 4);
        JsonNode ideas = detail.get("ideas");
        assertThat(ideas).hasSize(4);
        assertThat(ideas.get(0).get("type").asText()).isEqualTo("INSIGHT");
        assertThat(ideas.get(0).get("quote").asText()).contains("stop replying"); // drawn from the transcript
        assertThat(ideas.get(0).get("hooks")).isNotEmpty();
        assertThat(detail.get("source").get("ideaCount").asInt()).isEqualTo(4);

        // 2 ideas + the full source, × 3 formats, × 1 draft = 9 pieces.
        String request = """
                {"sourceId":%d,"includeFullSource":true,"ideaIds":[%d,%d],
                 "formats":["LINKEDIN_POST","X_THREAD","INSTAGRAM_CAROUSEL"],"draftsPerFormat":1,"translate":false}"""
                .formatted(source, ideas.get(0).get("id").asLong(), ideas.get(1).get("id").asLong());
        MvcResult started = batch(token, company, request);
        assertThat(started.getResponse().getStatus()).isEqualTo(202);
        JsonNode batch = awaitBatch(token, read(started).get("id").asLong());
        assertThat(batch.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(batch.get("completed").asInt()).isEqualTo(9);

        JsonNode queue = read(mvc.perform(get("/api/approvals?companyId=" + company).header("Authorization", "Bearer " + token)).andReturn());
        assertThat(queue).hasSize(9);
        JsonNode thread = find(queue, "X_THREAD");
        assertThat(thread.get("platform").asText()).isEqualTo("X");
        assertThat(thread.get("contentJson").asText()).contains("1/ ").contains("2/ ");
        assertThat(find(queue, "INSTAGRAM_CAROUSEL").get("type").asText()).isEqualTo("CAROUSEL");
        assertThat(find(queue, "INSTAGRAM_CAROUSEL").get("contentJson").asText()).contains("Slide 1:");
        assertThat(find(queue, "LINKEDIN_POST").get("contentJson").asText()).contains("#marketing");

        // A thread can't go out through GoHighLevel; a LinkedIn post can (demo connection).
        long threadId = thread.get("id").asLong();
        mvc.perform(post("/api/assets/" + threadId + "/approve").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        assertThat(mvc.perform(post("/api/assets/" + threadId + "/deploy").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"platform\":\"GOHIGHLEVEL\"}")).andReturn()
                .getResponse().getContentAsString()).contains("aren't published through GoHighLevel");
        long postId = find(queue, "LINKEDIN_POST").get("id").asLong();
        mvc.perform(post("/api/assets/" + postId + "/approve").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(post("/api/assets/" + postId + "/deploy").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"platform\":\"GOHIGHLEVEL\"}"))
                .andExpect(status().isAccepted());

        // Each draft was recorded as an AI call against the customer.
        JsonNode calls = read(mvc.perform(get("/api/companies/" + company + "/ai-interactions?limit=50")
                .header("Authorization", "Bearer " + token)).andReturn());
        List<String> kinds = new ArrayList<>();
        calls.forEach(c -> kinds.add(c.get("kind").asText()));
        assertThat(kinds).contains("CONTENT_IDEAS", "STUDIO:LINKEDIN_POST", "STUDIO:X_THREAD", "STUDIO:INSTAGRAM_CAROUSEL");
    }

    @Test
    void badRequestsAreRefusedBeforeAnyAiCall() throws Exception {
        String token = register();
        long company = customer(token);
        long source = source(token, company);
        long other = source(token, company);
        JsonNode ideas = analyze(token, source, 3).get("ideas");
        long otherIdea = analyze(token, other, 1).get("ideas").get(0).get("id").asLong();
        long idea = ideas.get(0).get("id").asLong();

        assertThat(batch(token, company, "{\"sourceId\":%d,\"includeFullSource\":true,\"formats\":[],\"draftsPerFormat\":1}"
                .formatted(source)).getResponse().getStatus()).isEqualTo(400);
        assertThat(batch(token, company, "{\"sourceId\":%d,\"includeFullSource\":true,\"formats\":[\"MYSPACE_POST\"],\"draftsPerFormat\":1}"
                .formatted(source)).getResponse().getContentAsString()).contains("Unknown format");
        assertThat(batch(token, company, "{\"sourceId\":%d,\"ideaIds\":[%d],\"formats\":[\"X_POST\"],\"draftsPerFormat\":1}"
                .formatted(source, otherIdea)).getResponse().getContentAsString()).contains("isn't one of this source's ideas");
        assertThat(batch(token, company, "{\"sourceId\":%d,\"includeFullSource\":false,\"formats\":[\"X_POST\"],\"draftsPerFormat\":1}"
                .formatted(source)).getResponse().getContentAsString()).contains("at least one idea");
        // 3 ideas + source = 4 × 11 formats = 44 > 40.
        String eleven = String.join("\",\"", Arrays.stream(ContentFormat.values()).limit(11).map(Enum::name).toList());
        assertThat(batch(token, company, "{\"sourceId\":%d,\"includeFullSource\":true,\"ideaIds\":[%d,%d,%d],\"formats\":[\"%s\"],\"draftsPerFormat\":1}"
                .formatted(source, idea, ideas.get(1).get("id").asLong(), ideas.get(2).get("id").asLong(), eleven))
                .getResponse().getContentAsString()).contains("at most 40");
        assertThat(batch(token, company, "{\"sourceId\":%d,\"includeFullSource\":true,\"formats\":[\"X_POST\"],\"draftsPerFormat\":4}"
                .formatted(source)).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void anotherAgencyCantSeeOrUseTheSourcesAndLowerRolesCantGenerate() throws Exception {
        String ownerA = register();
        long company = customer(ownerA);
        long source = source(ownerA, company);
        String ownerB = register();

        mvc.perform(get("/api/companies/" + company + "/studio/sources").header("Authorization", "Bearer " + ownerB))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/studio/sources/" + source).header("Authorization", "Bearer " + ownerB))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/studio/sources/" + source + "/analyze").header("Authorization", "Bearer " + ownerB))
                .andExpect(status().isNotFound());
        assertThat(batch(ownerB, company, "{\"sourceId\":%d,\"includeFullSource\":true,\"formats\":[\"X_POST\"],\"draftsPerFormat\":1}"
                .formatted(source)).getResponse().getStatus()).isEqualTo(404);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "analyst", null, List.of(new SimpleGrantedAuthority("ROLE_ANALYST"))));
        assertThatThrownBy(() -> studio.startBatch(company, new BatchRequest(source, true, List.of(), List.of("X_POST"), 1, false)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void everyFormatHasAnAgentAndOnlySinglePostsArePublishable() {
        for (ContentFormat f : ContentFormat.values()) {
            assertThat(f.guidance).as(f.name()).hasSizeGreaterThan(80);
            if (f.publishable) {
                assertThat(f.assetType).as(f.name()).isEqualTo(AssetType.SOCIAL_MEDIA);
                assertThat(f.ghlPlatform).as(f.name()).isNotBlank();
            } else {
                assertThat(f.ghlPlatform).as(f.name()).isNull();
            }
        }
    }

    static JsonNode find(JsonNode queue, String format) {
        for (JsonNode item : queue) {
            if (format.equals(item.get("asset").get("contentFormat").asText())) return item.get("asset");
        }
        throw new AssertionError("no " + format + " draft");
    }
}
