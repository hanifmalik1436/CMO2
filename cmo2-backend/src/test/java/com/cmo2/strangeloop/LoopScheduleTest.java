package com.cmo2.strangeloop;

import com.cmo2.strangeloop.domain.Cadence;
import com.cmo2.strangeloop.schedule.LoopQuartz;
import com.cmo2.strangeloop.schedule.LoopScheduleService;
import com.cmo2.strangeloop.dto.LoopDtos.ScheduleRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.Scheduler;
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

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Per-customer cadence: set through the API, held as a Quartz trigger in the customer's timezone,
 * moved when the timezone changes, and firing a real SCHEDULED cycle through Quartz.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoopScheduleTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    Scheduler scheduler;
    @Autowired
    LoopScheduleService schedules;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private String register() throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"sched.%s@example.com","password":"superSecret123","fullName":"Owner","organizationName":"Agency"}
                        """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn();
        String token = json.readTree(res.getResponse().getContentAsString()).get("accessToken").asText();
        mvc.perform(post("/api/billing/simulate-activation").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PRO\"}")).andExpect(status().isOk());
        return token;
    }

    private long customer(String token, String timezone) throws Exception {
        MvcResult res = mvc.perform(post("/api/companies").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Acme\"}"))
                .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(put("/api/companies/" + id + "/profile").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Acme\",\"timezone\":\"" + timezone + "\"}"))
                .andExpect(status().isOk());
        return id;
    }

    private JsonNode setSchedule(String token, long companyId, String body) throws Exception {
        MvcResult res = mvc.perform(put("/api/companies/" + companyId + "/loop/schedule").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(res.getResponse().getContentAsString());
    }

    @Test
    void buildsQuartzCronForEachCadence() {
        assertThat(LoopQuartz.cron(Cadence.DAILY, LocalTime.of(9, 5), null, null)).isEqualTo("0 5 9 * * ?");
        assertThat(LoopQuartz.cron(Cadence.WEEKLY, LocalTime.of(18, 0), 1, null)).isEqualTo("0 0 18 ? * MON");
        assertThat(LoopQuartz.cron(Cadence.WEEKLY, LocalTime.of(7, 30), 7, null)).isEqualTo("0 30 7 ? * SUN");
        assertThat(LoopQuartz.cron(Cadence.MONTHLY, LocalTime.of(6, 0), null, 28)).isEqualTo("0 0 6 28 * ?");
    }

    @Test
    void aWeeklyScheduleRunsAtTheCustomersLocalTimeAndFollowsTimezoneChanges() throws Exception {
        String token = register();
        long company = customer(token, "Europe/London");

        JsonNode s = setSchedule(token, company, "{\"cadence\":\"WEEKLY\",\"timeOfDay\":\"09:00\",\"dayOfWeek\":1}");
        assertThat(s.get("cadence").asText()).isEqualTo("WEEKLY");
        assertThat(s.get("timezone").asText()).isEqualTo("Europe/London");
        ZonedDateTime next = Instant.parse(s.get("nextRunAt").asText()).atZone(ZoneId.of("Europe/London"));
        assertThat(next.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(next.toLocalTime()).isEqualTo(LocalTime.of(9, 0));

        // The customer moves to New York: the same 09:00 Monday, now New York time.
        mvc.perform(put("/api/companies/" + company + "/profile").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Acme\",\"timezone\":\"America/New_York\"}"))
                .andExpect(status().isOk());
        MvcResult res = mvc.perform(get("/api/companies/" + company + "/loop/schedule").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        JsonNode moved = json.readTree(res.getResponse().getContentAsString());
        ZonedDateTime nextNy = Instant.parse(moved.get("nextRunAt").asText()).atZone(ZoneId.of("America/New_York"));
        assertThat(nextNy.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(nextNy.toLocalTime()).isEqualTo(LocalTime.of(9, 0));

        mvc.perform(get("/api/loop/schedules").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$[0].cadence").value("WEEKLY"));

        // Off removes the Quartz job.
        JsonNode off = setSchedule(token, company, "{\"cadence\":\"OFF\"}");
        assertThat(off.get("cadence").asText()).isEqualTo("OFF");
        assertThat(scheduler.checkExists(JobKey.jobKey("company-" + company, "strange-loop"))).isFalse();
    }

    @Test
    void aMonthlyScheduleKeepsToDaysEveryMonthHas() throws Exception {
        String token = register();
        long company = customer(token, "Europe/London");
        mvc.perform(put("/api/companies/" + company + "/loop/schedule").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cadence\":\"MONTHLY\",\"dayOfMonth\":31}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/companies/" + company + "/loop/schedule").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cadence\":\"DAILY\",\"timeOfDay\":\"25:00\"}"))
                .andExpect(status().isBadRequest());
        JsonNode s = setSchedule(token, company, "{\"cadence\":\"MONTHLY\",\"dayOfMonth\":28,\"timeOfDay\":\"06:30\"}");
        assertThat(Instant.parse(s.get("nextRunAt").asText()).atZone(ZoneId.of("Europe/London")).getDayOfMonth()).isEqualTo(28);
    }

    @Test
    void theQuartzJobStartsAScheduledCycle() throws Exception {
        String token = register();
        long company = customer(token, "Europe/London");
        setSchedule(token, company, "{\"cadence\":\"DAILY\",\"timeOfDay\":\"03:00\"}");

        scheduler.triggerJob(JobKey.jobKey("company-" + company, "strange-loop"));

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            MvcResult res = mvc.perform(get("/api/companies/" + company + "/loop/cycles").header("Authorization", "Bearer " + token))
                    .andReturn();
            JsonNode cycles = json.readTree(res.getResponse().getContentAsString());
            assertThat(cycles).isNotEmpty();
            assertThat(cycles.get(0).get("triggerType").asText()).isEqualTo("SCHEDULED");
            assertThat(cycles.get(0).get("status").asText()).isEqualTo("COMPLETED");
        });
        mvc.perform(get("/api/companies/" + company + "/loop/schedule").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.lastScheduledRunAt").isNotEmpty());
    }

    @Test
    void onlyTheOwnerOrAnAdminSetsSchedulesAndOtherAgenciesCantSeeThem() throws Exception {
        String ownerA = register();
        long company = customer(ownerA, "Europe/London");
        String ownerB = register();

        mvc.perform(get("/api/companies/" + company + "/loop/schedule").header("Authorization", "Bearer " + ownerB))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/companies/" + company + "/loop/schedule").header("Authorization", "Bearer " + ownerB)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cadence\":\"DAILY\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/loop/schedules").header("Authorization", "Bearer " + ownerB))
                .andExpect(jsonPath("$.length()").value(0));

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "analyst", null, List.of(new SimpleGrantedAuthority("ROLE_ANALYST"))));
        assertThatThrownBy(() -> schedules.set(company, new ScheduleRequest("DAILY", "09:00", null, null)))
                .isInstanceOf(AccessDeniedException.class);
    }
}
