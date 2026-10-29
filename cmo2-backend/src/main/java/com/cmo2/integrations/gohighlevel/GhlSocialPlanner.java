package com.cmo2.integrations.gohighlevel;

import com.cmo2.integrations.gohighlevel.capability.Actor;
import com.cmo2.integrations.gohighlevel.capability.GhlAction.CreateSocialPost;
import com.cmo2.integrations.gohighlevel.capability.GhlAction.ListSocialAccounts;
import com.cmo2.integrations.gohighlevel.capability.GhlAction.ListUsers;
import com.cmo2.integrations.gohighlevel.capability.GhlActionExecutor;
import com.cmo2.shared.exceptions.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A customer's GHL Social Planner: the social accounts they connected in their sub-account, and
 * scheduling an approved post to them. Every call goes through {@link GhlActionExecutor}, so a
 * post can't leave without an approval reference.
 *
 * <p>Response shapes follow GHL's published docs (results.accounts[], results.post._id), read
 * defensively because they're unverified against a live sub-account.
 */
@Service
public class GhlSocialPlanner {

    private final GhlActionExecutor executor;

    public GhlSocialPlanner(GhlActionExecutor executor) {
        this.executor = executor;
    }

    public record SocialAccount(String id, String name, String platform, boolean expired) {}

    /** {@code postId} is null if GHL accepted the post without returning its id. */
    public record ScheduledPost(String postId, Instant scheduledFor, List<String> accountIds) {}

    public List<SocialAccount> accounts(Long companyId) {
        JsonNode body = executor.execute(companyId, new ListSocialAccounts(), Actor.HUMAN, null);
        JsonNode list = body == null ? null : body.path("results").path("accounts");
        if (list == null || !list.isArray()) list = body == null ? null : body.path("accounts");
        List<SocialAccount> out = new ArrayList<>();
        if (list == null || !list.isArray()) return out;
        for (JsonNode a : list) {
            String id = a.path("id").asText("");
            if (id.isBlank() || a.path("deleted").asBoolean(false)) continue;
            out.add(new SocialAccount(id, a.path("name").asText(null), a.path("platform").asText(null),
                    a.path("isExpired").asBoolean(false)));
        }
        return out;
    }

    /**
     * Schedules {@code text} on the chosen accounts, or on every connected account that hasn't
     * expired when none are chosen. Only accounts the customer actually connected are accepted.
     */
    public ScheduledPost schedule(Long companyId, String text, List<String> accountIds, List<String> mediaUrls,
                                  Instant at, String approvalRef) {
        Map<String, SocialAccount> connected = accounts(companyId).stream()
                .collect(Collectors.toMap(SocialAccount::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        List<String> targets;
        if (accountIds == null || accountIds.isEmpty()) {
            targets = connected.values().stream().filter(a -> !a.expired()).map(SocialAccount::id).toList();
            if (targets.isEmpty()) {
                throw ApiException.badRequest("This customer has no working social accounts connected in GoHighLevel");
            }
        } else {
            for (String id : accountIds) {
                SocialAccount a = connected.get(id);
                if (a == null) throw ApiException.badRequest("That social account isn't connected in this customer's GoHighLevel");
                if (a.expired()) throw ApiException.badRequest((a.name() == null ? "A social account" : a.name())
                        + " has expired in GoHighLevel; the customer needs to reconnect it");
            }
            targets = List.copyOf(accountIds);
        }

        CreateSocialPost post;
        try {
            post = new CreateSocialPost(targets, text, mediaUrls, at, postingUser(companyId));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        JsonNode created;
        try {
            created = executor.execute(companyId, post, Actor.HUMAN, approvalRef);
        } catch (GhlApiException e) {
            throw GhlPostUnconfirmedException.isAmbiguous(e) ? new GhlPostUnconfirmedException(e) : e;
        }
        String postId = text(created, "results", "post", "_id");
        if (postId == null) postId = text(created, "post", "_id");
        if (postId == null) postId = text(created, "results", "post", "id");
        return new ScheduledPost(postId, at, targets);
    }

    /** The sub-account user posts are made as: its first admin, else its first user. */
    String postingUser(Long companyId) {
        JsonNode body = executor.execute(companyId, new ListUsers(), Actor.HUMAN, null);
        JsonNode users = body == null ? null : body.path("users");
        if (users == null || !users.isArray() || users.isEmpty()) {
            throw ApiException.badRequest("GoHighLevel returned no users for this sub-account, and posts need one to post as");
        }
        for (JsonNode u : users) {
            if ("admin".equalsIgnoreCase(u.path("roles").path("role").asText(""))
                    && !u.path("id").asText("").isBlank()) {
                return u.path("id").asText();
            }
        }
        String first = users.get(0).path("id").asText("");
        if (first.isBlank()) throw ApiException.badRequest("GoHighLevel returned users without ids");
        return first;
    }

    private static String text(JsonNode node, String... path) {
        if (node == null) return null;
        JsonNode n = node;
        for (String p : path) n = n.path(p);
        String v = n.asText("");
        return v.isBlank() ? null : v;
    }
}
