package com.cmo2.docs.service;

import com.cmo2.docs.client.ConfluenceClient;
import com.cmo2.docs.client.ConfluenceClient.ConfluencePage;
import com.cmo2.docs.client.DocClientException;
import com.cmo2.docs.client.LinearClient;
import com.cmo2.docs.client.LinearClient.LinearProject;
import com.cmo2.docs.client.NotionClient;
import com.cmo2.docs.client.NotionClient.NotionPage;
import com.cmo2.docs.domain.CompanyDocTargets;
import com.cmo2.docs.domain.DocProvider;
import com.cmo2.docs.dto.DocsDtos.DocTargetsResponse;
import com.cmo2.docs.dto.DocsDtos.UpdateDocTargetsRequest;
import com.cmo2.docs.repository.CompanyDocTargetsRepository;
import com.cmo2.docs.service.DocConnectionService.Resolved;
import com.cmo2.shared.audit.AuditService;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.tenancy.domain.Company;
import com.cmo2.tenancy.repository.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Each customer's area in the agency's workspace tools: a Notion page, a Confluence page and a
 * Linear project. Provisioning only creates what's missing, so it's safe to re-run, and one tool
 * failing never stops the others.
 */
@Service
public class DocTargetService {

    private static final Logger log = LoggerFactory.getLogger(DocTargetService.class);

    private final CompanyDocTargetsRepository repo;
    private final DocConnectionService connections;
    private final CompanyRepository companies;
    private final NotionClient notion;
    private final ConfluenceClient confluence;
    private final LinearClient linear;
    private final AuditService audit;

    public DocTargetService(CompanyDocTargetsRepository repo, DocConnectionService connections,
                            CompanyRepository companies, NotionClient notion, ConfluenceClient confluence,
                            LinearClient linear, AuditService audit) {
        this.repo = repo;
        this.connections = connections;
        this.companies = companies;
        this.notion = notion;
        this.confluence = confluence;
        this.linear = linear;
        this.audit = audit;
    }

    // ---- owner-facing ------------------------------------------------------------------

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public List<DocTargetsResponse> list() {
        return repo.findByTenantId(TenantContext.requireTenantId()).stream().map(DocTargetService::toResponse).toList();
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public DocTargetsResponse provisionForCompany(Long companyId) {
        Long tenantId = TenantContext.requireTenantId();
        Company company = ownedCompany(tenantId, companyId);
        return toResponse(provision(tenantId, company.getId(), company.getName()));
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public DocTargetsResponse update(Long companyId, UpdateDocTargetsRequest req) {
        Long tenantId = TenantContext.requireTenantId();
        ownedCompany(tenantId, companyId);
        CompanyDocTargets t = repo.findByTenantIdAndCompanyId(tenantId, companyId).orElseGet(() -> newTargets(tenantId, companyId));
        try {
            if (req.notionPageId() != null) {
                String id = blankToNull(req.notionPageId());
                t.setNotionPageId(id == null ? null : NotionClient.normalizeId(id));
                t.setNotionUrl(id == null ? null : "https://www.notion.so/" + t.getNotionPageId());
            }
            if (req.confluencePageId() != null) {
                String id = blankToNull(req.confluencePageId());
                if (id != null && !id.matches("^\\d{1,32}$")) throw new DocClientException("Confluence page id must be numeric");
                if (!Objects.equals(id, t.getConfluencePageId())) t.setConfluenceUrl(null);
                t.setConfluencePageId(id);
            }
            if (req.linearProjectId() != null) {
                String id = blankToNull(req.linearProjectId());
                if (id != null && !id.matches("^[0-9a-fA-F-]{36}$")) throw new DocClientException("Linear project id must be a UUID");
                if (!Objects.equals(id, t.getLinearProjectId())) t.setLinearUrl(null);
                t.setLinearProjectId(id);
            }
        } catch (DocClientException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        t = repo.save(t);
        audit.record("DOC_TARGETS_UPDATED", "Company", String.valueOf(companyId), null);
        return toResponse(t);
    }

    // ---- internal ------------------------------------------------------------------------

    /** Creates whatever this customer is missing in each connected tool. Never throws for a tool failure. */
    @Transactional
    public CompanyDocTargets provision(Long tenantId, Long companyId, String companyName) {
        Optional<Resolved> n = connections.resolve(tenantId, DocProvider.NOTION);
        Optional<Resolved> c = connections.resolve(tenantId, DocProvider.CONFLUENCE);
        Optional<Resolved> l = connections.resolve(tenantId, DocProvider.LINEAR);
        CompanyDocTargets t = repo.findByTenantIdAndCompanyId(tenantId, companyId).orElse(null);
        if (n.isEmpty() && c.isEmpty() && l.isEmpty()) {
            return t;
        }
        if (t == null) {
            t = newTargets(tenantId, companyId);
        }

        String title = "CMO² · " + companyName;
        String intro = "Strange Loop records for " + companyName + ". After every cycle CMO² adds a page here "
                + "with that cycle's decisions, tasks, the content it produced, and the prompts it used.";
        List<String> errors = new ArrayList<>();

        if (n.isPresent() && t.getNotionPageId() == null) {
            try {
                NotionPage page = notion.createPage(n.get().secret(), n.get().notionRoot(), title, intro);
                t.setNotionPageId(page.id());
                t.setNotionUrl(page.url());
            } catch (DocClientException e) {
                errors.add("Notion: " + e.getMessage());
            }
        }
        if (c.isPresent() && t.getConfluencePageId() == null) {
            try {
                Resolved r = c.get();
                String parent = r.cfg("parentPageId") != null ? r.cfg("parentPageId") : r.cfg("homepageId");
                // Confluence titles must be unique within a space, so include the customer's id.
                ConfluencePage page = confluence.createPage(r.confluenceAuth(), r.cfg("spaceId"), parent,
                        title + " (#" + companyId + ")", intro);
                t.setConfluencePageId(page.id());
                t.setConfluenceUrl(page.url());
            } catch (DocClientException e) {
                errors.add("Confluence: " + e.getMessage());
            }
        }
        if (l.isPresent() && t.getLinearProjectId() == null) {
            try {
                LinearProject project = linear.createProject(l.get().secret(), l.get().cfg("teamId"), title,
                        "Tasks CMO² tracks for " + companyName + ".");
                t.setLinearProjectId(project.id());
                t.setLinearUrl(project.url());
            } catch (DocClientException e) {
                errors.add("Linear: " + e.getMessage());
            }
        }

        String error = errors.isEmpty() ? null : String.join(" | ", errors);
        t.setLastError(error == null || error.length() <= 1000 ? error : error.substring(0, 1000));
        if (error != null) {
            log.warn("Workspace setup for company {} was incomplete: {}", companyId, error);
        }
        return repo.save(t);
    }

    public Optional<CompanyDocTargets> find(Long tenantId, Long companyId) {
        return repo.findByTenantIdAndCompanyId(tenantId, companyId);
    }

    private Company ownedCompany(Long tenantId, Long companyId) {
        return companies.findById(companyId)
                .filter(c -> tenantId.equals(c.getTenantId()))
                .orElseThrow(() -> ApiException.notFound("Company not found"));
    }

    private static CompanyDocTargets newTargets(Long tenantId, Long companyId) {
        CompanyDocTargets t = new CompanyDocTargets();
        t.setTenantId(tenantId);
        t.setCompanyId(companyId);
        return t;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static DocTargetsResponse toResponse(CompanyDocTargets t) {
        if (t == null) return null;
        return new DocTargetsResponse(t.getCompanyId(), t.getNotionPageId(), t.getNotionUrl(),
                t.getConfluencePageId(), t.getConfluenceUrl(), t.getLinearProjectId(), t.getLinearUrl(), t.getLastError());
    }
}
