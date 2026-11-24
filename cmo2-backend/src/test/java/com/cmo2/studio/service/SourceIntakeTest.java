package com.cmo2.studio.service;

import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.studio.service.SourceIntake.Extracted;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reading web pages safely, and Smart Schedule's slots. */
class SourceIntakeTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String serve(String path, int status, String contentType, String body, String location) throws Exception {
        if (server == null) {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.start();
        }
        server.createContext(path, ex -> {
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", contentType);
            if (location != null) ex.getResponseHeaders().add("Location", location);
            ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) ex.getResponseBody().write(out);
            ex.close();
        });
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @Test
    void aPageKeepsItsArticleTextAndTitleAndDropsTheRest() throws Exception {
        String url = serve("/post", 200, "text/html; charset=utf-8", """
                <html><head><title>Why solar quotes stall</title><script>track()</script></head><body>
                <nav>Home · Pricing · Login</nav>
                <article><h1>Why solar quotes stall</h1><p>Most homeowners just stop replying.</p>
                <ul><li>Show the monthly saving.</li><li>Call back within an hour.</li></ul></article>
                <footer>© 2026 Northwind</footer></body></html>""", null);

        Extracted page = new SourceIntake(null, true).fromLink(url, null);

        assertThat(page.title()).isEqualTo("Why solar quotes stall");
        assertThat(page.kind()).isEqualTo("LINK");
        assertThat(page.originUrl()).isEqualTo(url);
        assertThat(page.text()).contains("Most homeowners just stop replying.\nShow the monthly saving.\nCall back within an hour.")
                .doesNotContain("Pricing").doesNotContain("track()").doesNotContain("Northwind");
    }

    @Test
    void redirectsAreFollowedAndCheckedAgain() throws Exception {
        String target = serve("/final", 200, "text/plain", "The real article text.", null);
        String start = serve("/old", 301, "text/plain", "", target);

        assertThat(new SourceIntake(null, true).fromLink(start, "Mine").text()).isEqualTo("The real article text.");
        // With the guard on, the local server itself is refused, before anything is fetched.
        assertThatThrownBy(() -> new SourceIntake(null, false).fromLink(start, null))
                .isInstanceOf(ApiException.class).hasMessageContaining("private network");
    }

    @Test
    void privateAndSpecialAddressesAreNotPublic() throws Exception {
        for (String ip : new String[]{"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254",
                "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "::ffff:10.0.0.1"}) {
            assertThat(SourceIntake.isPublic(InetAddress.getByName(ip))).as(ip).isFalse();
        }
        assertThat(SourceIntake.isPublic(InetAddress.getByName("93.184.216.34"))).isTrue();
        assertThat(SourceIntake.isPublic(InetAddress.getByName("2606:4700::1111"))).isTrue();
    }

    @Test
    void aMissingPageAndANonPageAreClearErrors() throws Exception {
        String missing = serve("/gone", 404, "text/html", "<p>nope</p>", null);
        String image = serve("/logo.png", 200, "image/png", "PNG", null);
        SourceIntake intake = new SourceIntake(null, true);

        assertThatThrownBy(() -> intake.fromLink(missing, null)).hasMessageContaining("answered 404");
        assertThatThrownBy(() -> intake.fromLink(image, null)).hasMessageContaining("isn't a web page");
        assertThatThrownBy(() -> intake.fromLink("ftp://example.com/x", null)).hasMessageContaining("Only http and https");
    }

    @Test
    void smartScheduleGivesEachPlatformItsOwnDayAndSkipsWeekendsWhereItShould() {
        ZoneId zone = ZoneId.of("America/Chicago");
        Instant friday = ZonedDateTime.of(2026, 11, 20, 15, 0, 0, 0, zone).toInstant();
        LocalDate monday = LocalDate.of(2026, 11, 23);
        SmartSchedule schedule = new SmartSchedule(zone, friday, Map.of("LINKEDIN", Set.of(monday)));

        Set<LocalDate> linkedIn = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            ZonedDateTime at = schedule.next("LINKEDIN").atZone(zone);
            assertThat(at.getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
            assertThat(at.toLocalDate()).isNotEqualTo(monday);          // already has a suggested post
            assertThat(linkedIn.add(at.toLocalDate())).isTrue();
            assertThat(at.getHour()).isEqualTo(8);
        }
        assertThat(schedule.next("FACEBOOK").atZone(zone).toLocalDate()).isEqualTo(LocalDate.of(2026, 11, 21)); // Saturday is fine
        assertThat(schedule.next("TIKTOK")).isNull();                   // GHL can't post it, so no suggestion

        SmartSchedule full = new SmartSchedule(zone, friday, Map.of());
        int given = 0;
        while (full.next("INSTAGRAM") != null) given++;
        assertThat(given).isEqualTo(SmartSchedule.DAYS);
    }
}
