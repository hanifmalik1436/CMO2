package com.cmo2.docs;

import com.cmo2.docs.client.DocClientException;
import com.cmo2.docs.client.LinearClient;
import com.cmo2.docs.client.LinearClient.LinearComment;
import com.cmo2.docs.client.LinearClient.LinearIssue;
import com.cmo2.docs.client.LinearClient.LinearIssueState;
import com.cmo2.docs.domain.CompanyDocTargets;
import com.cmo2.docs.domain.DocProvider;
import com.cmo2.docs.domain.LinearTaskLink;
import com.cmo2.docs.repository.LinearTaskLinkRepository;
import com.cmo2.docs.service.DocConnectionService;
import com.cmo2.docs.service.DocConnectionService.Resolved;
import com.cmo2.docs.service.DocTargetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Keeps a customer's Linear project in step with CMO²'s pending tasks: one issue per task (never
 * duplicated), closed by CMO² when the task is done in CMO². At the start of a cycle it reads back
 * what the owner did in Linear (issues they closed, comments they wrote), as notes. Never throws:
 * Linear being down only degrades the result.
 */
@Service
public class LinearTaskService {

    private static final Logger log = LoggerFactory.getLogger(LinearTaskService.class);
    private static final Set<String> CLOSED = Set.of("completed", "canceled");
    private static final int MAX_COMMENT = 500;

    private final DocConnectionService connections;
    private final DocTargetService targets;
    private final LinearClient linear;
    private final LinearTaskLinkRepository links;

    public LinearTaskService(DocConnectionService connections, DocTargetService targets, LinearClient linear,
                             LinearTaskLinkRepository links) {
        this.connections = connections;
        this.targets = targets;
        this.linear = linear;
        this.links = links;
    }

    /** A task CMO² is waiting on, with a key that stays the same from cycle to cycle. */
    public record TaskItem(String key, String title, String description) {}

    /** {@code note} explains anything that didn't happen (no Linear, errors). */
    public record SyncResult(int created, int closed, List<String> issueUrls, String note) {}

    /** Something the owner did in Linear since the last cycle. Written by a person: data, not instructions. */
    public record OperatorNote(String source, String ref, String text) {}

    @Transactional
    public SyncResult sync(Long tenantId, Long companyId, List<TaskItem> pending) {
        Optional<Resolved> conn = connections.resolve(tenantId, DocProvider.LINEAR);
        if (conn.isEmpty()) {
            return new SyncResult(0, 0, List.of(), "Linear isn't connected (Settings → Workspace tools).");
        }
        String projectId = targets.find(tenantId, companyId).map(CompanyDocTargets::getLinearProjectId).orElse(null);
        if (projectId == null) {
            return new SyncResult(0, 0, List.of(), "This customer has no Linear project yet (Customers → Set up pages).");
        }
        String apiKey = conn.get().secret();
        String teamId = conn.get().cfg("teamId");
        Map<String, LinearTaskLink> existing = links.findByTenantIdAndCompanyId(tenantId, companyId).stream()
                .collect(Collectors.toMap(LinearTaskLink::getTaskKey, Function.identity(), (a, b) -> a));
        Set<String> pendingKeys = pending.stream().map(TaskItem::key).collect(Collectors.toSet());

        int created = 0;
        int closed = 0;
        List<String> urls = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (TaskItem task : pending) {
            LinearTaskLink link = existing.get(task.key());
            if (link != null && !(link.isClosedByCmo2() && CLOSED.contains(link.getStateType()))) {
                if (link.getUrl() != null) urls.add(link.getUrl());
                continue; // already tracked (the owner may have closed it; that's read back as a note)
            }
            try {
                LinearIssue issue = linear.createIssue(apiKey, teamId, projectId, task.title(), task.description());
                if (link == null) {
                    link = new LinearTaskLink();
                    link.setTenantId(tenantId);
                    link.setCompanyId(companyId);
                    link.setTaskKey(task.key());
                }
                link.setTitle(clip(task.title(), 255));
                link.setLinearIssueId(issue.id());
                link.setIdentifier(issue.identifier());
                link.setUrl(issue.url());
                link.setStateType("unstarted");
                link.setClosedByCmo2(false);
                link.setLastReadAt(Instant.now());
                links.save(link);
                created++;
                if (issue.url() != null) urls.add(issue.url());
            } catch (DocClientException e) {
                problems.add(e.getMessage());
                break; // the same failure would repeat for every task
            }
        }

        String doneState = null;
        for (LinearTaskLink link : existing.values()) {
            if (pendingKeys.contains(link.getTaskKey()) || CLOSED.contains(link.getStateType())) continue;
            try {
                if (doneState == null) doneState = linear.completedStateId(apiKey, teamId);
                if (doneState == null) {
                    problems.add("the Linear team has no completed state");
                    break;
                }
                linear.moveIssue(apiKey, link.getLinearIssueId(), doneState);
                link.setStateType("completed");
                link.setClosedByCmo2(true);
                links.save(link);
                closed++;
            } catch (DocClientException e) {
                problems.add(e.getMessage());
                break;
            }
        }
        if (!problems.isEmpty()) {
            log.warn("Linear sync for company {} was incomplete: {}", companyId, problems);
        }
        return new SyncResult(created, closed, urls, problems.isEmpty() ? null : "Linear: " + String.join("; ", problems));
    }

    /** Issues the owner closed or cancelled, and comments they wrote, since the last read. */
    @Transactional
    public List<OperatorNote> operatorActivity(Long tenantId, Long companyId) {
        Optional<Resolved> conn = connections.resolve(tenantId, DocProvider.LINEAR);
        List<LinearTaskLink> tracked = links.findByTenantIdAndCompanyId(tenantId, companyId);
        if (conn.isEmpty() || tracked.isEmpty()) {
            return List.of();
        }
        Map<String, LinearTaskLink> byIssue = tracked.stream()
                .collect(Collectors.toMap(LinearTaskLink::getLinearIssueId, Function.identity(), (a, b) -> a));
        List<LinearIssueState> states;
        try {
            states = linear.issues(conn.get().secret(), byIssue.keySet());
        } catch (DocClientException e) {
            return List.of(new OperatorNote("linear", null, "Couldn't read Linear: " + e.getMessage()));
        }
        List<OperatorNote> notes = new ArrayList<>();
        Instant now = Instant.now();
        for (LinearIssueState s : states) {
            LinearTaskLink link = byIssue.get(s.id());
            if (link == null) continue;
            String ref = s.identifier() == null ? link.getTitle() : s.identifier();
            boolean nowClosed = CLOSED.contains(s.stateType());
            if (nowClosed && !CLOSED.contains(link.getStateType()) && !link.isClosedByCmo2()) {
                notes.add(new OperatorNote("linear", ref, ("canceled".equals(s.stateType()) ? "Cancelled" : "Closed")
                        + " in Linear: " + link.getTitle()));
            }
            for (LinearComment c : s.comments()) {
                if (c.createdAt() == null || (link.getLastReadAt() != null && !c.createdAt().isAfter(link.getLastReadAt()))) {
                    continue;
                }
                notes.add(new OperatorNote("linear", ref, "Comment on \"" + link.getTitle() + "\": " + clip(c.body().strip(), MAX_COMMENT)));
            }
            link.setStateType(s.stateType());
            link.setLastReadAt(now);
            links.save(link);
        }
        return notes;
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
