package com.cmo2.integrations.gohighlevel.capability;

import com.cmo2.integrations.core.Platform;
import com.cmo2.integrations.core.PlatformCredentials;
import com.cmo2.integrations.domain.PlatformConnection;
import com.cmo2.integrations.gohighlevel.GhlApiException;
import com.cmo2.integrations.gohighlevel.GhlGateway;
import com.cmo2.integrations.gohighlevel.capability.GhlAction.AddContactTags;
import com.cmo2.integrations.gohighlevel.capability.GhlAction.CountContacts;
import com.cmo2.integrations.gohighlevel.capability.GhlAction.CreateSocialPost;
import com.cmo2.integrations.gohighlevel.capability.GhlAction.ListCustomValues;
import com.cmo2.integrations.gohighlevel.capability.GhlAction.ListSocialAccounts;
import com.cmo2.integrations.gohighlevel.capability.GhlAction.ListUsers;
import com.cmo2.integrations.gohighlevel.capability.GhlAction.ReadLocation;
import com.cmo2.integrations.gohighlevel.capability.GhlAction.RemoveContactTags;
import com.cmo2.integrations.gohighlevel.capability.GhlAction.UpdateCustomValue;
import com.cmo2.integrations.repository.PlatformConnectionRepository;
import com.cmo2.integrations.service.ConnectionService;
import com.cmo2.shared.audit.AuditService;
import com.cmo2.shared.exceptions.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The only way a Strange Loop stage or a person acts on a customer's GHL sub-account: check
 * the action against its risk tier, run it through {@link GhlGateway}, and audit it.
 */
@Service
public class GhlActionExecutor {

    private final CapabilityGuard guard;
    private final PlatformConnectionRepository connectionRepo;
    private final ConnectionService connectionService;
    private final GhlGateway gateway;
    private final AuditService audit;

    public GhlActionExecutor(CapabilityGuard guard, PlatformConnectionRepository connectionRepo,
                             ConnectionService connectionService, GhlGateway gateway, AuditService audit) {
        this.guard = guard;
        this.connectionRepo = connectionRepo;
        this.connectionService = connectionService;
        this.gateway = gateway;
        this.audit = audit;
    }

    /**
     * @param approvalRef what approved a client-facing action (e.g. an approved asset id); ignored for lower tiers
     */
    @Transactional(noRollbackFor = GhlApiException.class) // a GHL refusal must not undo the caller's record of it
    public JsonNode execute(Long companyId, GhlAction action, Actor actor, String approvalRef) {
        GhlCapability capability = action.capability();
        guard.check(capability, actor, approvalRef);

        PlatformConnection conn = connectionRepo.findFirstByCompanyIdAndPlatform(companyId, Platform.GOHIGHLEVEL)
                .orElseThrow(() -> ApiException.notFound("This customer has no GoHighLevel connection"));
        PlatformCredentials creds = connectionService.credentials(conn);
        String location = creds.locationId();
        if (location == null || location.isBlank()) {
            throw ApiException.badRequest("This customer's GHL connection has no sub-account id");
        }

        JsonNode result = dispatch(action, location, creds.bearerToken());

        audit.record("GHL_ACTION", "Company", String.valueOf(companyId),
                capability + " (" + capability.tier + ") by " + actor
                        + (approvalRef == null || approvalRef.isBlank() ? "" : ", approval " + approvalRef));
        return result;
    }

    private JsonNode dispatch(GhlAction action, String location, String token) {
        if (action instanceof ReadLocation) {
            return gateway.get("/locations/" + location, Map.of(), token, location);
        }
        if (action instanceof CountContacts) {
            long total = gateway.count("/contacts/", Map.of("locationId", location), "contacts", token, location);
            return JsonNodeFactory.instance.objectNode().put("total", total);
        }
        if (action instanceof ListSocialAccounts) {
            return gateway.get("/social-media-posting/" + location + "/accounts", Map.of(), token, location);
        }
        if (action instanceof ListCustomValues) {
            return gateway.get("/locations/" + location + "/customValues", Map.of(), token, location);
        }
        if (action instanceof ListUsers) {
            return gateway.get("/users/", Map.of("locationId", location), token, location);
        }
        if (action instanceof CreateSocialPost p) {
            // Always "scheduled": a post that is due now is scheduled a few minutes ahead by the caller,
            // which leaves time to delete it in the Social Planner if something is wrong.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", "post");
            body.put("status", "scheduled");
            body.put("accountIds", p.accountIds());
            body.put("summary", p.summary());
            body.put("userId", p.userId());
            body.put("scheduleDate", p.scheduledAt().toString());
            if (!p.mediaUrls().isEmpty()) {
                body.put("media", p.mediaUrls().stream()
                        .map(url -> Map.of("url", url, "type", mimeType(url)))
                        .toList());
            }
            return gateway.post("/social-media-posting/" + location + "/posts", body, token, location);
        }
        if (action instanceof AddContactTags a) {
            return gateway.post("/contacts/" + a.contactId() + "/tags", Map.of("tags", a.tags()), token, location);
        }
        if (action instanceof RemoveContactTags r) {
            return gateway.delete("/contacts/" + r.contactId() + "/tags", Map.of("tags", r.tags()), token, location);
        }
        if (action instanceof UpdateCustomValue u) {
            Map<String, Object> body = new LinkedHashMap<>();
            if (u.name() != null && !u.name().isBlank()) body.put("name", u.name());
            body.put("value", u.value());
            return gateway.put("/locations/" + location + "/customValues/" + u.customValueId(), body, token, location);
        }
        throw new ApiException(HttpStatus.NOT_IMPLEMENTED,
                action.capability() + " passed its safety check but is not wired to GoHighLevel yet");
    }

    /** GHL wants each media item's MIME type; it is inferred from the link's file extension. */
    static String mimeType(String url) {
        String path = url.toLowerCase();
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".gif")) return "image/gif";
        if (path.endsWith(".webp")) return "image/webp";
        if (path.endsWith(".mp4")) return "video/mp4";
        if (path.endsWith(".mov")) return "video/quicktime";
        return "image/jpeg";
    }
}
