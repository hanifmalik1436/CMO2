package com.cmo2.studio;

import com.cmo2.studio.dto.StudioDtos.LinkSourceRequest;
import com.cmo2.studio.service.StudioService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The redesigned Content Studio on the demo AI provider (docs/plans/2026-11-22-content-studio-redesign.md):
 * every way in (paste, file, recording, link), the background idea search with hook scores, the
 * library's favourites and usage figures, and Smart Schedule's suggested times.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContentStudioLibraryTest {

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

    private String register() throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"library.%s@example.com","password":"superSecret123","fullName":"Owner","organizationName":"Agency"}
                        """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn();
        String token = read(res).get("accessToken").asText();
        mvc.perform(post("/api/billing/simulate-activation").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PRO\"}")).andExpect(status().isOk());
        return token;
    }

    private long customer(String token) throws Exception {
        long id = read(mvc.perform(post("/api/companies").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Northwind Solar\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        mvc.perform(put("/api/companies/" + id + "/profile").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Northwind Solar\",\"timezone\":\"America/Chicago\"}"))
                .andExpect(status().isOk());
        return id;
    }

    private JsonNode paste(String token, long company, String body) throws Exception {
        return read(mvc.perform(post("/api/companies/" + company + "/studio/sources").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "title", "Podcast", "kind", "TRANSCRIPT", "body", body))))
                .andExpect(status().isCreated()).andReturn());
    }

    private JsonNode upload(String token, long company, String name, String type, byte[] bytes, int expected) throws Exception {
        return read(mvc.perform(multipart("/api/companies/" + company + "/studio/sources/upload")
                        .file(new MockMultipartFile("file", name, type, bytes)).header("Authorization", "Bearer " + token))
                .andExpect(status().is(expected)).andReturn());
    }

    private JsonNode settled(String token, long source) {
        JsonNode[] last = new JsonNode[1];
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            last[0] = read(mvc.perform(get("/api/studio/sources/" + source).header("Authorization", "Bearer " + token)).andReturn());
            assertThat(last[0].get("source").get("status").asText()).isNotIn("PENDING", "ANALYZING");
        });
        return last[0];
    }

    private JsonNode read(MvcResult res) throws Exception {
        return json.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private long aiCalls(String token, long company, String kind) throws Exception {
        JsonNode calls = read(mvc.perform(get("/api/companies/" + company + "/ai-interactions?limit=100")
                .header("Authorization", "Bearer " + token)).andReturn());
        long n = 0;
        for (JsonNode c : calls) if (kind.equals(c.get("kind").asText())) n++;
        return n;
    }

    @Test
    void aNewSourceIsSearchedForIdeasInTheBackgroundWithHookScoresBestFirst() throws Exception {
        String token = register();
        long company = customer(token);

        JsonNode created = paste(token, company, ContentStudioFlowTest.TRANSCRIPT);
        assertThat(created.get("source").get("status").asText()).isEqualTo("PENDING");

        JsonNode detail = settled(token, created.get("source").get("id").asLong());
        assertThat(detail.get("source").get("status").asText()).isEqualTo("READY");
        JsonNode ideas = detail.get("ideas");
        assertThat(ideas.size()).isGreaterThan(1);
        List<Integer> scores = new ArrayList<>();
        ideas.forEach(i -> scores.add(i.get("viralityScore").asInt()));
        assertThat(scores).isSortedAccordingTo((a, b) -> b - a);
        assertThat(ideas.get(0).get("viralityReason").asText()).isNotBlank();
        assertThat(detail.get("source").get("ideaCount").asInt()).isEqualTo(ideas.size());
    }

    @Test
    void textTooShortForIdeasIsMarkedWithoutAnAiCall() throws Exception {
        String token = register();
        long company = customer(token);

        long source = paste(token, company, "Hello, write some content.").get("source").get("id").asLong();
        JsonNode detail = settled(token, source);

        assertThat(detail.get("source").get("status").asText()).isEqualTo("TOO_SHORT");
        assertThat(detail.get("ideas")).isEmpty();
        assertThat(aiCalls(token, company, "CONTENT_IDEAS")).isZero();
    }

    @Test
    void filesBecomeTextAndUnsupportedOnesAreRefused() throws Exception {
        String token = register();
        long company = customer(token);

        JsonNode txt = upload(token, company, "notes.md", "text/markdown",
                "# Notes\nCall back within an hour.".getBytes(StandardCharsets.UTF_8), 201);
        assertThat(txt.get("source").get("kind").asText()).isEqualTo("UPLOAD");
        assertThat(txt.get("source").get("title").asText()).isEqualTo("notes");
        assertThat(txt.get("source").get("originFile").asText()).isEqualTo("notes.md");
        assertThat(txt.get("body").asText()).contains("Call back within an hour.");

        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        Document doc = new Document();
        PdfWriter.getInstance(doc, pdf);
        doc.open();
        doc.add(new Paragraph("Solar quotes stall when they show cost, not savings."));
        doc.close();
        assertThat(upload(token, company, "brief.pdf", "application/pdf", pdf.toByteArray(), 201).get("body").asText())
                .contains("Solar quotes stall when they show cost");

        ByteArrayOutputStream docx = new ByteArrayOutputStream();
        try (XWPFDocument word = new XWPFDocument()) {
            word.createParagraph().createRun().setText("A video walkthrough builds more trust than a PDF.");
            word.write(docx);
        }
        assertThat(upload(token, company, "script.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx.toByteArray(), 201)
                .get("body").asText()).contains("video walkthrough");

        assertThat(upload(token, company, "virus.exe", "application/octet-stream", new byte[]{1, 2, 3}, 400)
                .get("message").asText()).contains("can read");
        assertThat(upload(token, company, "fake.pdf", "application/pdf", "not a pdf".getBytes(StandardCharsets.UTF_8), 400)
                .get("message").asText()).contains("isn't a readable PDF");
    }

    @Test
    void recordingsAreTranscribedAndOnlyTheTextIsKept() throws Exception {
        String token = register();
        long company = customer(token);

        JsonNode rec = read(mvc.perform(multipart("/api/companies/" + company + "/studio/sources/recording")
                        .file(new MockMultipartFile("audio", "recording.webm", "audio/webm", new byte[4096]))
                        .param("title", "Recording – 9:41 AM")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andReturn());
        assertThat(rec.get("source").get("kind").asText()).isEqualTo("RECORDING");
        assertThat(rec.get("source").get("title").asText()).isEqualTo("Recording – 9:41 AM");
        assertThat(rec.get("body").asText()).contains("Demo transcript");
        assertThat(aiCalls(token, company, "TRANSCRIPTION")).isEqualTo(1);

        // An audio file uploaded through the file picker is transcribed the same way.
        JsonNode audio = upload(token, company, "interview.m4a", "audio/mp4", new byte[2048], 201);
        assertThat(audio.get("source").get("kind").asText()).isEqualTo("RECORDING");
        assertThat(settled(token, rec.get("source").get("id").asLong()).get("source").get("status").asText()).isEqualTo("READY");
    }

    @Test
    void linksIntoAPrivateNetworkAreRefused() throws Exception {
        String token = register();
        long company = customer(token);

        for (String url : List.of("http://127.0.0.1:8080/admin", "http://169.254.169.254/latest/meta-data", "http://10.0.0.5/",
                "file:///etc/passwd")) {
            MvcResult res = mvc.perform(post("/api/companies/" + company + "/studio/sources/link")
                    .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(new LinkSourceRequest(url, null)))).andReturn();
            assertThat(res.getResponse().getStatus()).as(url).isEqualTo(400);
        }
    }

    @Test
    void favouritesLastUsedAndGeneratedCountsShowInTheLibrary() throws Exception {
        String token = register();
        long company = customer(token);
        long source = paste(token, company, ContentStudioFlowTest.TRANSCRIPT).get("source").get("id").asLong();
        settled(token, source);

        mvc.perform(put("/api/studio/sources/" + source + "/star").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"starred\":true}")).andExpect(status().isOk());
        long batch = read(mvc.perform(post("/api/companies/" + company + "/studio/batches").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"sourceId":%d,"includeFullSource":true,"formats":["X_POST","X_LONG_POST"],"draftsPerFormat":1}"""
                        .formatted(source))).andExpect(status().isAccepted()).andReturn()).get("id").asLong();
        awaitBatch(token, batch);

        JsonNode row = read(mvc.perform(get("/api/companies/" + company + "/studio/sources")
                .header("Authorization", "Bearer " + token)).andReturn()).get(0);
        assertThat(row.get("starred").asBoolean()).isTrue();
        assertThat(row.get("lastUsedAt").isNull()).isFalse();
        assertThat(row.get("generated").asInt()).isEqualTo(2);
    }

    @Test
    void smartScheduleSuggestsOnePostPerPlatformPerDayInTheCustomersTimezone() throws Exception {
        String token = register();
        long company = customer(token);
        long source = paste(token, company, ContentStudioFlowTest.TRANSCRIPT).get("source").get("id").asLong();
        JsonNode ideas = settled(token, source).get("ideas");

        long batch = read(mvc.perform(post("/api/companies/" + company + "/studio/batches").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"sourceId":%d,"includeFullSource":true,"ideaIds":[%d],
                         "formats":["X_POST","LINKEDIN_POST","FACEBOOK_POST","X_THREAD"],"draftsPerFormat":2,"smartSchedule":true}"""
                        .formatted(source, ideas.get(0).get("id").asLong()))).andExpect(status().isAccepted()).andReturn()).get("id").asLong();
        assertThat(awaitBatch(token, batch).get("completed").asInt()).isEqualTo(16);

        ZoneId chicago = ZoneId.of("America/Chicago");
        Map<String, Set<LocalDate>> days = new HashMap<>();
        JsonNode queue = read(mvc.perform(get("/api/approvals?companyId=" + company).header("Authorization", "Bearer " + token)).andReturn());
        int suggested = 0;
        for (JsonNode item : queue) {
            JsonNode asset = item.get("asset");
            String format = asset.get("contentFormat").asText();
            if (format.equals("X_THREAD")) {
                assertThat(asset.get("suggestedPublishAt").isNull()).as("not publishable").isTrue();
                continue;
            }
            ZonedDateTime at = Instant.parse(asset.get("suggestedPublishAt").asText()).atZone(chicago);
            assertThat(at.toInstant()).isAfter(Instant.now());
            assertThat(days.computeIfAbsent(asset.get("platform").asText(), p -> new HashSet<>()).add(at.toLocalDate()))
                    .as("one %s post a day", asset.get("platform").asText()).isTrue();
            if (format.equals("LINKEDIN_POST")) {
                assertThat(at.toLocalTime()).isEqualTo(LocalTime.of(8, 30));
                assertThat(at.getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
            }
            suggested++;
        }
        assertThat(suggested).isEqualTo(12);

        // Nothing went to GoHighLevel: every draft still waits for approval.
        for (JsonNode item : queue) assertThat(item.get("asset").get("status").asText()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void anotherAgencyCantAddToOrStarTheSourcesAndViewersCantAddAny() throws Exception {
        String ownerA = register();
        long company = customer(ownerA);
        long source = paste(ownerA, company, ContentStudioFlowTest.TRANSCRIPT).get("source").get("id").asLong();
        settled(ownerA, source);
        String ownerB = register();

        upload(ownerB, company, "notes.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8), 404);
        mvc.perform(multipart("/api/companies/" + company + "/studio/sources/recording")
                .file(new MockMultipartFile("audio", "r.webm", "audio/webm", new byte[100])).header("Authorization", "Bearer " + ownerB))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/companies/" + company + "/studio/sources/link").header("Authorization", "Bearer " + ownerB)
                .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://example.com\"}")).andExpect(status().isNotFound());
        mvc.perform(put("/api/studio/sources/" + source + "/star").header("Authorization", "Bearer " + ownerB)
                .contentType(MediaType.APPLICATION_JSON).content("{\"starred\":true}")).andExpect(status().isNotFound());

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "viewer", null, List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))));
        assertThatThrownBy(() -> studio.uploadSource(company, "a.txt", "text/plain", new byte[]{65}, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> studio.recordSource(company, "a.webm", "audio/webm", new byte[]{65}, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> studio.linkSource(company, new LinkSourceRequest("https://example.com", null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> studio.star(source, true)).isInstanceOf(AccessDeniedException.class);
    }

    private JsonNode awaitBatch(String token, long batchId) {
        JsonNode[] last = new JsonNode[1];
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(150)).untilAsserted(() -> {
            last[0] = read(mvc.perform(get("/api/studio/batches/" + batchId).header("Authorization", "Bearer " + token)).andReturn());
            assertThat(last[0].get("status").asText()).isNotEqualTo("RUNNING");
        });
        return last[0];
    }
}
