package com.cmo2.docs;

import com.cmo2.docs.client.ConfluenceClient;
import com.cmo2.docs.client.ConfluenceClient.ConfluencePage;
import com.cmo2.docs.client.DocClientException;
import com.cmo2.docs.client.NotionClient;
import com.cmo2.docs.client.NotionClient.NotionPage;
import com.cmo2.docs.client.NotionClient.NotionParent;
import com.cmo2.docs.domain.CompanyDocTargets;
import com.cmo2.docs.domain.DocProvider;
import com.cmo2.docs.service.DocConnectionService;
import com.cmo2.docs.service.DocConnectionService.Resolved;
import com.cmo2.docs.service.DocTargetService;
import com.cmo2.shared.notify.TeamNotifier;
import com.cmo2.tenancy.domain.Company;
import com.cmo2.tenancy.repository.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Strange Loop Stage 3's writer. Each cycle's record goes to the customer's own area in the
 * agency's workspace tools — a child page under its Notion page and under its Confluence page —
 * and a summary with links goes to the team's Slack. Never throws: a flaky tool degrades the
 * result instead of breaking the cycle, and {@code targets} lists only tools actually written to.
 */
@Service
public class DocumentationService {

    private static final Logger log = LoggerFactory.getLogger(DocumentationService.class);

    private final DocConnectionService connections;
    private final DocTargetService targets;
    private final CompanyRepository companies;
    private final NotionClient notion;
    private final ConfluenceClient confluence;
    private final TeamNotifier team;

    public DocumentationService(DocConnectionService connections, DocTargetService targets, CompanyRepository companies,
                                NotionClient notion, ConfluenceClient confluence, TeamNotifier team) {
        this.connections = connections;
        this.targets = targets;
        this.companies = companies;
        this.notion = notion;
        this.confluence = confluence;
        this.team = team;
    }

    /**
     * Outcome of a documentation push; {@code links} maps tool name to the page written and
     * {@code pageIds} maps "notion"/"confluence" to its id (so the page can be read back later).
     */
    public record DocResult(boolean published, List<String> targets, String note, Map<String, String> links,
                            Map<String, String> pageIds) {}

    /** Current text of pages CMO² wrote, by tool; {@code problems} names any page that couldn't be read. */
    public record ReadBack(Map<String, String> texts, List<String> problems) {}

    public DocResult publishCycle(Long tenantId, Long companyId, int iterationNo, String title, String body) {
        Optional<Company> company = companies.findById(companyId).filter(c -> tenantId.equals(c.getTenantId()));
        if (company.isEmpty()) {
            return new DocResult(false, List.of(), "Customer not found for this agency.", Map.of(), Map.of());
        }
        String name = company.get().getName();
        Optional<Resolved> n = connections.resolve(tenantId, DocProvider.NOTION);
        Optional<Resolved> c = connections.resolve(tenantId, DocProvider.CONFLUENCE);
        if (n.isEmpty() && c.isEmpty()) {
            team.notify(tenantId, "*" + name + "* · Strange Loop cycle " + iterationNo + " finished (no Notion/Confluence connected).");
            return new DocResult(false, List.of(),
                    "No Notion or Confluence connected (Settings → Workspace tools).", Map.of(), Map.of());
        }

        CompanyDocTargets t = targets.find(tenantId, companyId).orElse(null);
        boolean missing = t == null
                || (n.isPresent() && t.getNotionPageId() == null)
                || (c.isPresent() && t.getConfluencePageId() == null);
        if (missing) {
            t = targets.provision(tenantId, companyId, name);
        }

        String pageTitle = name + " · cycle " + iterationNo + " · " + LocalDate.now(ZoneOffset.UTC);
        List<String> written = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, String> links = new LinkedHashMap<>();
        Map<String, String> pageIds = new LinkedHashMap<>();

        if (n.isPresent()) {
            if (t == null || t.getNotionPageId() == null) {
                notes.add("Notion: this customer's page couldn't be set up" + reason(t));
            } else {
                try {
                    NotionPage page = notion.createPage(n.get().secret(), NotionParent.page(t.getNotionPageId()), pageTitle, body);
                    written.add("notion");
                    pageIds.put("notion", page.id());
                    if (page.url() != null) links.put("Notion", page.url());
                } catch (DocClientException e) {
                    notes.add("Notion: " + e.getMessage());
                }
            }
        }
        if (c.isPresent()) {
            if (t == null || t.getConfluencePageId() == null) {
                notes.add("Confluence: this customer's page couldn't be set up" + reason(t));
            } else {
                try {
                    ConfluencePage page = confluence.createPage(c.get().confluenceAuth(), c.get().cfg("spaceId"),
                            t.getConfluencePageId(), pageTitle + " (#" + companyId + ")", body);
                    written.add("confluence");
                    pageIds.put("confluence", page.id());
                    if (page.url() != null) links.put("Confluence", page.url());
                } catch (DocClientException e) {
                    notes.add("Confluence: " + e.getMessage());
                }
            }
        }
        if (!notes.isEmpty()) {
            log.warn("Cycle {} record for company {} was incomplete: {}", iterationNo, companyId, notes);
        }

        team.notify(tenantId, slackSummary(name, iterationNo, title, links, notes));

        String note = written.isEmpty() ? String.join("; ", notes)
                : "Published to " + String.join(", ", written) + (notes.isEmpty() ? "." : " (" + String.join("; ", notes) + ")");
        return new DocResult(!written.isEmpty(), written, note, links, pageIds);
    }

    /** Reads back the pages CMO² wrote ("notion"/"confluence" → page id) to see what the owner changed. */
    public ReadBack readBack(Long tenantId, Map<String, String> pageIds) {
        Map<String, String> texts = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        String notionPage = pageIds.get("notion");
        if (notionPage != null) {
            connections.resolve(tenantId, DocProvider.NOTION).ifPresentOrElse(n -> {
                try {
                    texts.put("notion", notion.pageText(n.secret(), notionPage));
                } catch (DocClientException e) {
                    problems.add("Notion: " + e.getMessage());
                }
            }, () -> problems.add("Notion is no longer connected"));
        }
        String confluencePage = pageIds.get("confluence");
        if (confluencePage != null) {
            connections.resolve(tenantId, DocProvider.CONFLUENCE).ifPresentOrElse(c -> {
                try {
                    texts.put("confluence", confluence.pageText(c.confluenceAuth(), confluencePage));
                } catch (DocClientException e) {
                    problems.add("Confluence: " + e.getMessage());
                }
            }, () -> problems.add("Confluence is no longer connected"));
        }
        return new ReadBack(texts, problems);
    }

    private static String slackSummary(String name, int iterationNo, String title, Map<String, String> links, List<String> notes) {
        StringBuilder s = new StringBuilder("*").append(name).append("* · Strange Loop cycle ").append(iterationNo)
                .append(" finished — ").append(title);
        links.forEach((tool, url) -> s.append("\n• <").append(url).append("|").append(tool).append(">"));
        notes.forEach(note -> s.append("\n⚠ ").append(note));
        return s.toString();
    }

    private static String reason(CompanyDocTargets t) {
        return t != null && t.getLastError() != null ? " — " + t.getLastError() : "";
    }
}
