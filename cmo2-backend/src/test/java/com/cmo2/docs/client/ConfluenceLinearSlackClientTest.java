package com.cmo2.docs.client;

import com.cmo2.docs.client.ConfluenceClient.ConfluenceAuth;
import com.cmo2.docs.client.ConfluenceClient.ConfluencePage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ConfluenceLinearSlackClientTest {

    // ---- Confluence -------------------------------------------------------------------

    @Test
    void confluenceOnlyTalksToAtlassianCloudSites() {
        assertThat(ConfluenceClient.normalizeSite("acme.atlassian.net")).isEqualTo("https://acme.atlassian.net");
        assertThat(ConfluenceClient.normalizeSite("https://Acme.atlassian.net/wiki/spaces/X")).isEqualTo("https://acme.atlassian.net");
        assertThatThrownBy(() -> ConfluenceClient.normalizeSite("https://evil.example.com")).isInstanceOf(DocClientException.class);
        assertThatThrownBy(() -> ConfluenceClient.normalizeSite("https://atlassian.net.evil.com")).isInstanceOf(DocClientException.class);
        assertThatThrownBy(() -> ConfluenceClient.normalizeSite("https://169.254.169.254")).isInstanceOf(DocClientException.class);
    }

    @Test
    void confluenceContentIsEscaped() {
        String storage = ConfluenceClient.toStorage("# Plan <b>\n\nHello <script>alert(1)</script>\nline two");

        assertThat(storage).isEqualTo("<h2>Plan &lt;b&gt;</h2><p>Hello &lt;script&gt;alert(1)&lt;/script&gt;<br/>line two</p>");
    }

    @Test
    void confluenceCreatesAChildPageAndBuildsItsLink() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ConfluenceClient confluence = new ConfluenceClient(builder);
        String basic = "Basic " + Base64.getEncoder().encodeToString("me@acme.com:tok".getBytes(StandardCharsets.UTF_8));

        server.expect(requestTo("https://acme.atlassian.net/wiki/api/v2/pages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, basic))
                .andExpect(jsonPath("$.spaceId").value("98304"))
                .andExpect(jsonPath("$.parentId").value("123"))
                .andExpect(jsonPath("$.status").value("current"))
                .andExpect(jsonPath("$.body.representation").value("storage"))
                .andRespond(withSuccess("{\"id\":\"456\",\"_links\":{\"webui\":\"/spaces/CMO/pages/456/Cycle\"}}",
                        MediaType.APPLICATION_JSON));

        ConfluencePage page = confluence.createPage(new ConfluenceAuth("https://acme.atlassian.net", "me@acme.com", "tok"),
                "98304", "123", "Cycle", "body");

        assertThat(page.id()).isEqualTo("456");
        assertThat(page.url()).isEqualTo("https://acme.atlassian.net/wiki/spaces/CMO/pages/456/Cycle");
        server.verify();
    }

    // ---- Linear -----------------------------------------------------------------------

    private static final String TEAM = "00517556-fe71-46b2-9bdb-34cde8451dd0";

    @Test
    void linearSendsTheRawApiKeyAndCreatesAProjectForTheTeam() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://linear.test/graphql");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LinearClient linear = new LinearClient(builder);

        server.expect(requestTo("https://linear.test/graphql"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "lin_api_key"))
                .andExpect(jsonPath("$.variables.input.teamIds[0]").value(TEAM))
                .andExpect(jsonPath("$.variables.input.name").value("CMO² · Acme"))
                .andRespond(withSuccess("{\"data\":{\"projectCreate\":{\"success\":true,"
                        + "\"project\":{\"id\":\"proj-1\",\"url\":\"https://linear.app/x/project/acme\"}}}}", MediaType.APPLICATION_JSON));

        var project = linear.createProject("lin_api_key", TEAM, "CMO² · Acme", "desc");

        assertThat(project.id()).isEqualTo("proj-1");
        server.verify();
    }

    @Test
    void linearErrorsInsideA200AreFailures() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://linear.test/graphql");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LinearClient linear = new LinearClient(builder);
        server.expect(requestTo("https://linear.test/graphql"))
                .andRespond(withSuccess("{\"errors\":[{\"message\":\"Entity not found: Team\"}]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> linear.team("lin_api_key", TEAM))
                .isInstanceOf(DocClientException.class)
                .hasMessageContaining("Entity not found");
    }

    @Test
    void linearTeamIdsMustBeUuids() {
        assertThatThrownBy(() -> LinearClient.requireTeamId("ENG")).isInstanceOf(DocClientException.class);
    }

    // ---- Slack ------------------------------------------------------------------------

    @Test
    void slackOnlyPostsToSlackWebhooks() {
        assertThat(SlackClient.isSlackWebhook("https://hooks.slack.com/services/T0/B0/xyz")).isTrue();
        assertThat(SlackClient.isSlackWebhook("http://hooks.slack.com/services/T0/B0/xyz")).isFalse();
        assertThat(SlackClient.isSlackWebhook("https://hooks.slack.com.evil.com/services/x")).isFalse();
        assertThat(SlackClient.isSlackWebhook("https://example.com/services/x")).isFalse();
        assertThatThrownBy(() -> new SlackClient().post("http://localhost:8080/admin", "hi"))
                .isInstanceOf(DocClientException.class);
    }
}
