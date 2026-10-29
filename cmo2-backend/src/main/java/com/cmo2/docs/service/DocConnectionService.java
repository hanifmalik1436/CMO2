package com.cmo2.docs.service;

import com.cmo2.docs.client.ConfluenceClient;
import com.cmo2.docs.client.ConfluenceClient.ConfluenceAuth;
import com.cmo2.docs.client.ConfluenceClient.ConfluenceSpace;
import com.cmo2.docs.client.DocClientException;
import com.cmo2.docs.client.LinearClient;
import com.cmo2.docs.client.LinearClient.LinearTeam;
import com.cmo2.docs.client.NotionClient;
import com.cmo2.docs.client.NotionClient.NotionParent;
import com.cmo2.docs.client.SlackClient;
import com.cmo2.docs.domain.DocConnection;
import com.cmo2.docs.domain.DocProvider;
import com.cmo2.docs.dto.DocsDtos.DocConnectionResponse;
import com.cmo2.docs.dto.DocsDtos.SaveDocConnectionRequest;
import com.cmo2.docs.repository.DocConnectionRepository;
import com.cmo2.shared.audit.AuditService;
import com.cmo2.shared.crypto.AesGcmService;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.security.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The agency's connections to Notion, Confluence, Linear and Slack. Each save is verified against
 * the tool straight away, so the status shown is real. Secrets are encrypted and never returned.
 */
@Service
public class DocConnectionService {

    private static final TypeReference<LinkedHashMap<String, String>> CONFIG = new TypeReference<>() {};

    private final DocConnectionRepository repo;
    private final AesGcmService crypto;
    private final AuditService audit;
    private final ObjectMapper json;
    private final NotionClient notion;
    private final ConfluenceClient confluence;
    private final LinearClient linear;
    private final SlackClient slack;

    public DocConnectionService(DocConnectionRepository repo, AesGcmService crypto, AuditService audit,
                                ObjectMapper json, NotionClient notion, ConfluenceClient confluence,
                                LinearClient linear, SlackClient slack) {
        this.repo = repo;
        this.crypto = crypto;
        this.audit = audit;
        this.json = json;
        this.notion = notion;
        this.confluence = confluence;
        this.linear = linear;
        this.slack = slack;
    }

    /** A decrypted, ready-to-use connection. Never logged or returned to clients. */
    public record Resolved(DocProvider provider, String secret, Map<String, String> config) {
        public String cfg(String key) {
            return config.get(key);
        }

        public ConfluenceAuth confluenceAuth() {
            return new ConfluenceAuth(cfg("siteUrl"), cfg("email"), secret);
        }

        public NotionParent notionRoot() {
            return new NotionParent(cfg("rootId"), cfg("rootKind"), cfg("titleProperty"), cfg("rootUrl"));
        }
    }

    // ---- owner-facing ------------------------------------------------------------------

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public List<DocConnectionResponse> list() {
        Long tenantId = TenantContext.requireTenantId();
        Map<DocProvider, DocConnection> existing = new LinkedHashMap<>();
        repo.findByTenantId(tenantId).forEach(c -> existing.put(c.getProvider(), c));
        List<DocConnectionResponse> out = new ArrayList<>();
        for (DocProvider p : DocProvider.values()) {
            DocConnection c = existing.get(p);
            out.add(c == null
                    ? new DocConnectionResponse(p.name(), p.displayName, false, null, Map.of(), null, null, null)
                    : toResponse(c));
        }
        return out;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public DocConnectionResponse save(DocProvider provider, SaveDocConnectionRequest req) {
        Long tenantId = TenantContext.requireTenantId();
        DocConnection conn = repo.findByTenantIdAndProvider(tenantId, provider).orElseGet(() -> {
            DocConnection c = new DocConnection();
            c.setTenantId(tenantId);
            c.setProvider(provider);
            return c;
        });

        String secret = req.secret() == null ? null : req.secret().trim();
        if (secret == null || secret.isEmpty()) {
            if (conn.getSecretEnc() == null) {
                throw ApiException.badRequest(provider.displayName + " needs its " + secretLabel(provider));
            }
            secret = crypto.decrypt(conn.getSecretEnc());
        } else {
            if (provider == DocProvider.SLACK && !SlackClient.isSlackWebhook(secret)) {
                throw ApiException.badRequest("Slack webhook URLs must start with https://hooks.slack.com/services/");
            }
            conn.setSecretEnc(crypto.encrypt(secret));
            conn.setSecretLast4(secret.length() <= 4 ? secret : secret.substring(secret.length() - 4));
        }

        Map<String, String> config;
        try {
            config = settingsFrom(provider, req, readConfig(conn));
        } catch (DocClientException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        verifyInto(conn, provider, secret, config);
        conn = repo.save(conn);
        audit.record("DOC_CONNECTION_SAVED", "DocConnection", String.valueOf(conn.getId()),
                provider.displayName + " → " + conn.getStatus());
        return toResponse(conn);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public DocConnectionResponse test(DocProvider provider) {
        DocConnection conn = repo.findByTenantIdAndProvider(TenantContext.requireTenantId(), provider)
                .orElseThrow(() -> ApiException.notFound(provider.displayName + " isn't connected"));
        verifyInto(conn, provider, crypto.decrypt(conn.getSecretEnc()), readConfig(conn));
        return toResponse(repo.save(conn));
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public void delete(DocProvider provider) {
        repo.findByTenantIdAndProvider(TenantContext.requireTenantId(), provider).ifPresent(conn -> {
            repo.delete(conn);
            audit.record("DOC_CONNECTION_REMOVED", "DocConnection", String.valueOf(conn.getId()), provider.displayName);
        });
    }

    // ---- internal (no role check: the Strange Loop and listeners use these) -------------

    public Optional<Resolved> resolve(Long tenantId, DocProvider provider) {
        return repo.findByTenantIdAndProvider(tenantId, provider)
                .map(c -> new Resolved(provider, crypto.decrypt(c.getSecretEnc()), readConfig(c)));
    }

    // ---- helpers -------------------------------------------------------------------------

    /** Reads the request into a config map (keeping earlier values for fields left blank). */
    private Map<String, String> settingsFrom(DocProvider provider, SaveDocConnectionRequest req, Map<String, String> prev) {
        Map<String, String> c = new LinkedHashMap<>();
        switch (provider) {
            case NOTION -> c.put("rootId", NotionClient.normalizeId(pick(req.notionRootId(), prev.get("rootId"), "Notion page or database id")));
            case CONFLUENCE -> {
                c.put("siteUrl", ConfluenceClient.normalizeSite(pick(req.confluenceSiteUrl(), prev.get("siteUrl"), "Confluence site URL")));
                c.put("email", pick(req.confluenceEmail(), prev.get("email"), "Confluence account email").toLowerCase());
                c.put("spaceKey", ConfluenceClient.requireSpaceKey(pick(req.confluenceSpaceKey(), prev.get("spaceKey"), "Confluence space key")));
                String parent = req.confluenceParentPageId() != null ? req.confluenceParentPageId().trim() : prev.get("parentPageId");
                if (parent != null && !parent.isBlank()) {
                    if (!parent.matches("^\\d{1,32}$")) throw new DocClientException("Confluence parent page id must be numeric");
                    c.put("parentPageId", parent);
                }
            }
            case LINEAR -> c.put("teamId", LinearClient.requireTeamId(pick(req.linearTeamId(), prev.get("teamId"), "Linear team id")));
            case SLACK -> { }
        }
        return c;
    }

    /** Checks the tool, enriches the config (resolved ids and names), and records the outcome. */
    private void verifyInto(DocConnection conn, DocProvider provider, String secret, Map<String, String> config) {
        conn.setLastCheckedAt(Instant.now());
        try {
            switch (provider) {
                case NOTION -> {
                    config.put("botName", notion.botName(secret));
                    NotionParent root = notion.describeRoot(secret, config.get("rootId"));
                    config.put("rootKind", root.kind());
                    config.put("titleProperty", root.titleProperty());
                    if (root.url() != null) config.put("rootUrl", root.url());
                }
                case CONFLUENCE -> {
                    ConfluenceSpace space = confluence.space(
                            new ConfluenceAuth(config.get("siteUrl"), config.get("email"), secret), config.get("spaceKey"));
                    config.put("spaceId", space.id());
                    if (space.name() != null) config.put("spaceName", space.name());
                    if (space.homepageId() != null) config.put("homepageId", space.homepageId());
                }
                case LINEAR -> {
                    LinearTeam team = linear.team(secret, config.get("teamId"));
                    if (team.name() != null) config.put("teamName", team.name());
                }
                case SLACK -> slack.post(secret, ":white_check_mark: CMO² is connected to this channel.");
            }
            conn.setStatus(DocConnection.STATUS_CONNECTED);
            conn.setLastError(null);
        } catch (DocClientException e) {
            conn.setStatus(DocConnection.STATUS_ERROR);
            conn.setLastError(e.getMessage().length() > 1000 ? e.getMessage().substring(0, 1000) : e.getMessage());
        }
        conn.setConfigJson(writeConfig(config));
    }

    private DocConnectionResponse toResponse(DocConnection c) {
        Map<String, String> settings = new LinkedHashMap<>(readConfig(c));
        return new DocConnectionResponse(c.getProvider().name(), c.getProvider().displayName, true,
                "••••" + (c.getSecretLast4() == null ? "" : c.getSecretLast4()),
                settings, c.getStatus(), c.getLastError(), c.getLastCheckedAt());
    }

    private Map<String, String> readConfig(DocConnection c) {
        if (c.getConfigJson() == null || c.getConfigJson().isBlank()) return new LinkedHashMap<>();
        try {
            return json.readValue(c.getConfigJson(), CONFIG);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String writeConfig(Map<String, String> config) {
        try {
            return json.writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise workspace settings", e);
        }
    }

    private static String pick(String given, String previous, String label) {
        String v = given != null && !given.isBlank() ? given.trim() : previous;
        if (v == null || v.isBlank()) {
            throw new DocClientException(label + " is required");
        }
        return v;
    }

    private static String secretLabel(DocProvider p) {
        return switch (p) {
            case NOTION -> "integration token";
            case CONFLUENCE -> "API token";
            case LINEAR -> "API key";
            case SLACK -> "incoming webhook URL";
        };
    }
}
