package com.cmo2.docs.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Linear's GraphQL API with a personal API key (sent as the raw Authorization value — Linear's
 * convention for API keys). {@code projectCreate} needs {@code name} and {@code teamIds}
 * (checked against the live schema).
 */
@Component
public class LinearClient {

    private static final Pattern UUID = Pattern.compile("^[0-9a-fA-F-]{36}$");

    private final RestClient client;

    @Autowired
    public LinearClient(@Value("${cmo2.docs.linear-url:https://api.linear.app/graphql}") String url) {
        this(RestClient.builder().baseUrl(url));
    }

    LinearClient(RestClient.Builder builder) {
        this.client = builder.build();
    }

    public record LinearTeam(String id, String name, String key) {}

    public record LinearProject(String id, String url) {}

    public record LinearIssue(String id, String identifier, String url) {}

    public record LinearComment(String body, Instant createdAt, String author) {}

    /** {@code stateType}: Linear's workflow state type (triage, backlog, unstarted, started, completed, canceled). */
    public record LinearIssueState(String id, String identifier, String url, String title, String stateType,
                                   String stateName, List<LinearComment> comments) {}

    public static String requireTeamId(String teamId) {
        if (teamId == null || !UUID.matcher(teamId.trim()).matches()) {
            throw new DocClientException("Linear team id must be the team's UUID");
        }
        return teamId.trim();
    }

    public LinearTeam team(String apiKey, String teamId) {
        JsonNode data = graphql(apiKey, "query($id: String!) { team(id: $id) { id name key } }",
                Map.of("id", requireTeamId(teamId)));
        JsonNode team = data.path("team");
        if (team.isMissingNode() || team.isNull()) {
            throw new DocClientException("No Linear team with that id is visible to this API key");
        }
        return new LinearTeam(team.path("id").asText(), team.path("name").asText(null), team.path("key").asText(null));
    }

    public LinearProject createProject(String apiKey, String teamId, String name, String description) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", name.length() > 80 ? name.substring(0, 80) : name);
        input.put("teamIds", List.of(requireTeamId(teamId)));
        if (description != null) input.put("description", description);
        JsonNode data = graphql(apiKey,
                "mutation($input: ProjectCreateInput!) { projectCreate(input: $input) { success project { id url } } }",
                Map.of("input", input));
        JsonNode payload = data.path("projectCreate");
        if (!payload.path("success").asBoolean(false)) {
            throw new DocClientException("Linear did not create the project");
        }
        return new LinearProject(payload.path("project").path("id").asText(null),
                payload.path("project").path("url").asText(null));
    }

    public LinearIssue createIssue(String apiKey, String teamId, String projectId, String title, String description) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("teamId", requireTeamId(teamId));
        if (projectId != null && !projectId.isBlank()) input.put("projectId", projectId);
        input.put("title", title.length() > 250 ? title.substring(0, 250) : title);
        if (description != null) input.put("description", description);
        JsonNode payload = graphql(apiKey,
                "mutation($input: IssueCreateInput!) { issueCreate(input: $input) { success issue { id identifier url } } }",
                Map.of("input", input)).path("issueCreate");
        if (!payload.path("success").asBoolean(false)) {
            throw new DocClientException("Linear did not create the issue");
        }
        JsonNode issue = payload.path("issue");
        return new LinearIssue(issue.path("id").asText(null), issue.path("identifier").asText(null),
                issue.path("url").asText(null));
    }

    /** Current state and latest comments of the given issues (ids CMO² created). */
    public List<LinearIssueState> issues(String apiKey, Collection<String> ids) {
        if (ids.isEmpty()) return List.of();
        JsonNode nodes = graphql(apiKey, """
                query($ids: [ID!]) {
                  issues(filter: { id: { in: $ids } }, first: 100) {
                    nodes { id identifier url title state { type name }
                            comments(first: 20) { nodes { body createdAt user { name } } } }
                  }
                }""", Map.of("ids", List.copyOf(ids))).path("issues").path("nodes");
        List<LinearIssueState> out = new ArrayList<>();
        for (JsonNode n : nodes) {
            List<LinearComment> comments = new ArrayList<>();
            for (JsonNode c : n.path("comments").path("nodes")) {
                Instant at = null;
                try {
                    at = Instant.parse(c.path("createdAt").asText());
                } catch (Exception ignored) {
                    // unparseable timestamps are treated as unknown
                }
                comments.add(new LinearComment(c.path("body").asText(""), at, c.path("user").path("name").asText(null)));
            }
            out.add(new LinearIssueState(n.path("id").asText(), n.path("identifier").asText(null), n.path("url").asText(null),
                    n.path("title").asText(null), n.path("state").path("type").asText(null),
                    n.path("state").path("name").asText(null), comments));
        }
        return out;
    }

    /** The team's first "completed" workflow state (e.g. Done), or null if it has none. */
    public String completedStateId(String apiKey, String teamId) {
        JsonNode nodes = graphql(apiKey, """
                query($teamId: ID!) {
                  workflowStates(filter: { team: { id: { eq: $teamId } }, type: { eq: "completed" } }) {
                    nodes { id position }
                  }
                }""", Map.of("teamId", requireTeamId(teamId))).path("workflowStates").path("nodes");
        String best = null;
        double bestPosition = Double.MAX_VALUE;
        for (JsonNode n : nodes) {
            double position = n.path("position").asDouble(Double.MAX_VALUE);
            if (best == null || position < bestPosition) {
                best = n.path("id").asText(null);
                bestPosition = position;
            }
        }
        return best;
    }

    public void moveIssue(String apiKey, String issueId, String stateId) {
        JsonNode payload = graphql(apiKey,
                "mutation($id: String!, $input: IssueUpdateInput!) { issueUpdate(id: $id, input: $input) { success } }",
                Map.of("id", issueId, "input", Map.of("stateId", stateId))).path("issueUpdate");
        if (!payload.path("success").asBoolean(false)) {
            throw new DocClientException("Linear did not update the issue");
        }
    }

    private JsonNode graphql(String apiKey, String query, Map<String, Object> variables) {
        JsonNode response;
        try {
            response = client.post()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("query", query, "variables", variables))
                    .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            throw new DocClientException("Linear returned " + status + (status == 401 ? ": the API key was rejected" : ""));
        } catch (Exception e) {
            throw new DocClientException("Could not reach Linear: " + e.getMessage());
        }
        // GraphQL reports failures in the body with HTTP 200.
        if (response == null) {
            throw new DocClientException("Linear returned an empty response");
        }
        JsonNode errors = response.get("errors");
        if (errors != null && errors.isArray() && !errors.isEmpty()) {
            throw new DocClientException("Linear: " + errors.get(0).path("message").asText("request failed"));
        }
        return response.path("data");
    }
}
