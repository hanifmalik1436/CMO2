package com.cmo2.docs.client;

import com.cmo2.docs.client.NotionClient.NotionPage;
import com.cmo2.docs.client.NotionClient.NotionParent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NotionClientTest {

    private static final String BASE = "https://notion.test";
    private static final String ID = "3f6da080000280f9934ec6ff2c2653ad";

    private MockRestServiceServer server;
    private NotionClient notion;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        notion = new NotionClient(builder);
    }

    @Test
    void readsTheIdOutOfPastedUrlsAndDashedIds() {
        assertThat(NotionClient.normalizeId("https://app.notion.com/p/" + ID + "?v=3f6da080000280a5b85c000c7bf53467")).isEqualTo(ID);
        assertThat(NotionClient.normalizeId("https://www.notion.so/My-Page-Title-" + ID)).isEqualTo(ID);
        assertThat(NotionClient.normalizeId("https://www.notion.so/CMO2-Strange-Loop-Insights-" + ID + "?pvs=4")).isEqualTo(ID);
        assertThat(NotionClient.normalizeId("https://www.notion.so/acme/Weekly-Plan-cafe-" + ID)).isEqualTo(ID);
        assertThat(NotionClient.normalizeId("3f6da080-0002-80f9-934e-c6ff2c2653ad")).isEqualTo(ID);
        assertThatThrownBy(() -> NotionClient.normalizeId("not an id")).isInstanceOf(DocClientException.class);
    }

    @Test
    void splitsLongTextAndMakesHeadings() {
        String body = "# Insights\n\n" + "x".repeat(4500) + "\n\nshort";
        List<Map<String, Object>> blocks = NotionClient.blocks(body);

        assertThat(blocks).hasSize(5);
        assertThat(blocks.get(0).get("type")).isEqualTo("heading_2");
        assertThat(blocks.subList(1, 4)).allMatch(b -> "paragraph".equals(b.get("type")));
        assertThat(blocks.get(4).get("type")).isEqualTo("paragraph");
    }

    @Test
    void describesADatabaseRootAndFindsItsTitleColumn() {
        server.expect(requestTo(BASE + "/v1/databases/" + ID))
                .andExpect(header("Notion-Version", "2022-06-28"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok"))
                .andRespond(withSuccess("{\"object\":\"database\",\"url\":\"https://notion.so/db\","
                        + "\"properties\":{\"Status\":{\"type\":\"select\"},\"Name\":{\"type\":\"title\"}}}", MediaType.APPLICATION_JSON));

        NotionParent root = notion.describeRoot("tok", ID);

        assertThat(root.kind()).isEqualTo("database");
        assertThat(root.titleProperty()).isEqualTo("Name");
    }

    @Test
    void fallsBackToAPageRootWhenTheIdIsNotADatabase() {
        server.expect(requestTo(BASE + "/v1/databases/" + ID)).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        server.expect(requestTo(BASE + "/v1/pages/" + ID))
                .andRespond(withSuccess("{\"object\":\"page\",\"url\":\"https://notion.so/page\"}", MediaType.APPLICATION_JSON));

        assertThat(notion.describeRoot("tok", ID).kind()).isEqualTo("page");
    }

    @Test
    void createsARowInADatabaseRootUsingItsTitleColumn() {
        server.expect(requestTo(BASE + "/v1/pages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.parent.database_id").value(ID))
                .andExpect(jsonPath("$.properties.Name.title[0].text.content").value("CMO² · Acme"))
                .andRespond(withSuccess("{\"id\":\"p1\",\"url\":\"https://notion.so/p1\"}", MediaType.APPLICATION_JSON));

        NotionPage page = notion.createPage("tok", new NotionParent(ID, "database", "Name", null), "CMO² · Acme", "hello");

        assertThat(page.id()).isEqualTo("p1");
        assertThat(page.url()).isEqualTo("https://notion.so/p1");
        server.verify();
    }

    @Test
    void sendsAtMost100BlocksPerRequestAndAppendsTheRest() {
        String body = IntStream.range(0, 150).mapToObj(i -> "paragraph " + i).collect(Collectors.joining("\n\n"));
        server.expect(requestTo(BASE + "/v1/pages"))
                .andExpect(jsonPath("$.parent.page_id").value("parent-page"))
                .andExpect(jsonPath("$.properties.title.title[0].text.content").value("Cycle 1"))
                .andExpect(jsonPath("$.children.length()").value(100))
                .andRespond(withSuccess("{\"id\":\"p2\",\"url\":\"u\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/blocks/p2/children"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(jsonPath("$.children.length()").value(50))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        notion.createPage("tok", NotionParent.page("parent-page"), "Cycle 1", body);

        server.verify();
    }

    @Test
    void explainsAPageThatIsNotSharedWithTheIntegration() {
        server.expect(requestTo(BASE + "/v1/pages")).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"object\":\"error\",\"message\":\"Could not find page with ID: x. Make sure the relevant pages and databases are shared with your integration.\"}"));

        assertThatThrownBy(() -> notion.createPage("tok", NotionParent.page("x"), "t", "b"))
                .isInstanceOf(DocClientException.class)
                .hasMessageContaining("shared with your integration");
    }
}
