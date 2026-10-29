package com.cmo2.integrations.gohighlevel;

import com.cmo2.integrations.core.Platform;
import com.cmo2.integrations.domain.PlatformConnection;
import com.cmo2.integrations.gohighlevel.capability.Actor;
import com.cmo2.integrations.gohighlevel.capability.GhlAction.ListCustomValues;
import com.cmo2.integrations.gohighlevel.capability.GhlAction.UpdateCustomValue;
import com.cmo2.integrations.gohighlevel.capability.GhlActionExecutor;
import com.cmo2.integrations.repository.PlatformConnectionRepository;
import com.cmo2.integrations.service.ConnectionService;
import com.cmo2.shared.exceptions.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A customer's GHL custom values (the {@code {{custom_values.*}}} their Snapshot workflows, emails and
 * pages read). Listing is read-only; changing one goes through {@link GhlActionExecutor}, so it is
 * checked against its risk tier and audited.
 */
@Service
public class GhlCustomValues {

    static final int MAX_VALUE_PREVIEW = 300;

    private final GhlActionExecutor executor;
    private final PlatformConnectionRepository connections;
    private final ConnectionService connectionService;
    private final GoHighLevelConnector ghl;

    public GhlCustomValues(GhlActionExecutor executor, PlatformConnectionRepository connections,
                           ConnectionService connectionService, GoHighLevelConnector ghl) {
        this.executor = executor;
        this.connections = connections;
        this.connectionService = connectionService;
        this.ghl = ghl;
    }

    /** {@code value} is shortened for prompts; the full text is never needed to choose one. */
    public record CustomValue(String id, String name, String value) {}

    /** The customer's custom values, or none when it has no live GHL sub-account (e.g. demo). */
    public List<CustomValue> list(Long companyId) {
        if (!isLive(companyId)) {
            return List.of();
        }
        JsonNode body = executor.execute(companyId, new ListCustomValues(), Actor.AGENT, null);
        JsonNode values = body == null ? null : body.path("customValues");
        List<CustomValue> out = new ArrayList<>();
        if (values == null || !values.isArray()) return out;
        for (JsonNode v : values) {
            String id = v.path("id").asText("");
            if (id.isBlank()) continue;
            String value = v.path("value").asText("");
            out.add(new CustomValue(id, v.path("name").asText(null),
                    value.length() <= MAX_VALUE_PREVIEW ? value : value.substring(0, MAX_VALUE_PREVIEW) + "…"));
        }
        return out;
    }

    /** Replaces one custom value's text; {@code approvalRef} names what approved it (e.g. "experiment:12"). */
    public void update(Long companyId, String customValueId, String value, Actor actor, String approvalRef) {
        UpdateCustomValue action;
        try {
            action = new UpdateCustomValue(customValueId, null, value);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        executor.execute(companyId, action, actor, approvalRef);
    }

    private boolean isLive(Long companyId) {
        Optional<PlatformConnection> conn = connections.findFirstByCompanyIdAndPlatform(companyId, Platform.GOHIGHLEVEL);
        if (conn.isEmpty()) return false;
        try {
            return ghl.isLive(connectionService.credentials(conn.get()));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
