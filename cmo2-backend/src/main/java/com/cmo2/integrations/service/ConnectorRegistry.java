package com.cmo2.integrations.service;

import com.cmo2.integrations.core.Platform;
import com.cmo2.integrations.core.PlatformConnector;
import com.cmo2.shared.exceptions.ApiException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a {@link PlatformConnector} by {@link Platform}. Spring injects every
 * connector bean; new platforms register themselves simply by being a {@code @Component}.
 */
@Component
public class ConnectorRegistry {

    private final Map<Platform, PlatformConnector> connectors = new EnumMap<>(Platform.class);

    public ConnectorRegistry(List<PlatformConnector> beans) {
        for (PlatformConnector c : beans) {
            connectors.put(c.platform(), c);
        }
    }

    public PlatformConnector require(Platform platform) {
        PlatformConnector connector = connectors.get(platform);
        if (connector == null) {
            throw ApiException.badRequest("No connector available for " + platform.displayName
                    + " yet (arrives in Phase 8)");
        }
        return connector;
    }

    public boolean supports(Platform platform) {
        return connectors.containsKey(platform);
    }

    /** Metadata for every platform — which are connectable and which accept deployed assets. */
    public List<PlatformInfo> catalog() {
        List<PlatformInfo> out = new java.util.ArrayList<>();
        for (Platform p : Platform.values()) {
            PlatformConnector c = connectors.get(p);
            out.add(new PlatformInfo(p.name(), p.displayName, c != null, c != null && c.deploysAssets()));
        }
        return out;
    }

    public record PlatformInfo(String code, String name, boolean supported, boolean deploysAssets) {}
}
