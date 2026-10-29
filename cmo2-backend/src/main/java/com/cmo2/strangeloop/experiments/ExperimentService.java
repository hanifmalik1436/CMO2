package com.cmo2.strangeloop.experiments;

import com.cmo2.analytics.service.KpiTrendService.Trend;
import com.cmo2.assets.service.GenerationService;
import com.cmo2.integrations.core.Metrics;
import com.cmo2.integrations.gohighlevel.GhlApiException;
import com.cmo2.integrations.gohighlevel.GhlCustomValues;
import com.cmo2.integrations.gohighlevel.capability.Actor;
import com.cmo2.shared.audit.AuditService;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.shared.tenancy.TenantGuard;
import com.cmo2.tenancy.repository.CompanyRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stage 5's experiments: a hypothesis, one action from {@link ExperimentAction}, and its result.
 *
 * <ul>
 *   <li>A social post becomes a draft at once; the owner approving and publishing it (Approvals)
 *       is what applies the experiment.</li>
 *   <li>A custom value change waits for the owner here, unless auto-applying reversible changes is
 *       switched on; it then runs as an AI action through the capability guard.</li>
 *   <li>A suggestion is applied when the owner marks it done.</li>
 * </ul>
 * An applied experiment is measured in a later cycle: its metric this week against the week it was
 * proposed. One metric moving is evidence, not proof, and the result says so.
 */
@Service
public class ExperimentService {

    static final double FLAT_PERCENT = 5.0;
    private static final int MAX_LIST = 50;

    private final ExperimentRepository experiments;
    private final CompanyRepository companies;
    private final GenerationService generation;
    private final GhlCustomValues customValues;
    private final AuditService audit;
    private final ObjectMapper json;

    public ExperimentService(ExperimentRepository experiments, CompanyRepository companies, GenerationService generation,
                             GhlCustomValues customValues, AuditService audit, ObjectMapper json) {
        this.experiments = experiments;
        this.companies = companies;
        this.generation = generation;
        this.customValues = customValues;
        this.audit = audit;
        this.json = json;
    }

    /** One proposal as the AI filled it in; fields its action doesn't use are blank. */
    public record Proposal(String hypothesis, ExperimentAction action, String metric, String expectedDirection,
                           String postBrief, String customValueId, String customValueName, String newValue,
                           String suggestion) {}

    public record ExperimentView(Long id, Long iterationId, String hypothesis, String action, String riskTier,
                                 String status, String metric, String metricLabel, String expectedDirection,
                                 Map<String, String> params, Double baselineValue, Double resultValue, String outcome,
                                 String resultSummary, Long assetId, String decisionNote, Instant createdAt,
                                 Instant appliedAt, Instant measuredAt) {}

    /** For a cycle's record: what was decided and measured in the period, and what still waits on the owner. */
    public record RecordPart(List<String> decided, List<String> measured, List<Awaiting> awaiting) {}

    public record Awaiting(String key, String title, String detail) {}

    // ---- proposing (internal, loop threads) -------------------------------------------------

    @Transactional
    public Experiment propose(Long tenantId, Long companyId, Long iterationId, Proposal p, Double baseline,
                              boolean autoApplyReversible) {
        Experiment e = new Experiment();
        e.setTenantId(tenantId);
        e.setCompanyId(companyId);
        e.setIterationId(iterationId);
        e.setHypothesis(clip(p.hypothesis(), 500));
        e.setActionType(p.action());
        e.setCapability(p.action().capability == null ? null : p.action().capability.name());
        e.setRiskTier(p.action().tier().name());
        e.setMetric(p.metric());
        e.setExpectedDirection(p.expectedDirection());
        e.setBaselineValue(baseline);
        Map<String, String> params = new LinkedHashMap<>();
        switch (p.action()) {
            case DRAFT_SOCIAL_POST -> params.put("postBrief", p.postBrief());
            case UPDATE_CUSTOM_VALUE -> {
                params.put("customValueId", p.customValueId());
                params.put("customValueName", p.customValueName());
                params.put("newValue", p.newValue());
            }
            case SUGGESTION -> params.put("suggestion", p.suggestion());
        }
        e.setParamsJson(write(params));
        e = experiments.save(e);

        if (p.action() == ExperimentAction.DRAFT_SOCIAL_POST) {
            e.setAssetId(generation.generateForCycle(companyId, "SOCIAL_MEDIA", p.postBrief()).english().id());
            e = experiments.save(e);
        } else if (p.action() == ExperimentAction.UPDATE_CUSTOM_VALUE && autoApplyReversible) {
            applyCustomValue(e, Actor.AGENT, null);
        }
        audit.record("EXPERIMENT_PROPOSED", "Experiment", String.valueOf(e.getId()), p.action() + ": " + e.getHypothesis());
        return e;
    }

    @Transactional(readOnly = true)
    public long openCount(Long tenantId, Long companyId) {
        return experiments.countByTenantIdAndCompanyIdAndStatus(tenantId, companyId, ExperimentStatus.PROPOSED);
    }

    /** Measures applied experiments old enough to judge; returns a sentence per result. */
    @Transactional
    public List<String> measureDue(Long tenantId, Long companyId, Map<String, Trend> trends, Duration minAge) {
        List<String> out = new ArrayList<>();
        Instant cutoff = Instant.now().minus(minAge);
        for (Experiment e : experiments.findByTenantIdAndCompanyIdAndStatusOrderByCreatedAtAsc(tenantId, companyId, ExperimentStatus.APPLIED)) {
            if (e.getAppliedAt() == null || e.getAppliedAt().isAfter(cutoff)) continue;
            Trend t = trends.get(e.getMetric());
            if (t == null) continue; // no figure for that metric this week; try again next cycle
            measure(e, t.current());
            experiments.save(e);
            out.add(e.getResultSummary());
        }
        return out;
    }

    static void measure(Experiment e, double current) {
        e.setResultValue(current);
        e.setMeasuredAt(Instant.now());
        e.setStatus(ExperimentStatus.MEASURED);
        String label = Metrics.label(e.getMetric());
        Double base = e.getBaselineValue();
        if (base == null) {
            e.setOutcome("INCONCLUSIVE");
            e.setResultSummary(label + " is now " + num(current) + "; there was no starting figure to compare with.");
            return;
        }
        double change = base == 0 ? (current == 0 ? 0 : 100) * Math.signum(current) : 100.0 * (current - base) / Math.abs(base);
        String moved = Math.abs(change) < FLAT_PERCENT ? "flat" : change > 0 ? "up" : "down";
        e.setOutcome("flat".equals(moved) ? "INCONCLUSIVE" : moved.equals(e.getExpectedDirection()) ? "SUPPORTED" : "NOT_SUPPORTED");
        e.setResultSummary("Experiment #" + e.getId() + " (" + e.getHypothesis() + "): " + label + " " + num(base) + " → "
                + num(current) + " (" + (change >= 0 ? "+" : "") + num(change) + "%), "
                + switch (e.getOutcome()) {
                    case "SUPPORTED" -> "as expected";
                    case "NOT_SUPPORTED" -> "the opposite of what was expected";
                    default -> "no clear change";
                } + ". Other things changed that week too, so treat this as a signal, not proof.");
    }

    /** Short lines on measured experiments, newest first, for the next prompts. */
    @Transactional(readOnly = true)
    public List<String> recentResults(Long tenantId, Long companyId, int limit) {
        return experiments.findByTenantIdAndCompanyIdOrderByCreatedAtDesc(tenantId, companyId, PageRequest.of(0, 30)).stream()
                .filter(e -> e.getStatus() == ExperimentStatus.MEASURED && e.getResultSummary() != null)
                .limit(limit)
                .map(Experiment::getResultSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public RecordPart forRecord(Long tenantId, Long companyId, Instant from, Instant to) {
        List<String> decided = new ArrayList<>();
        List<String> measured = new ArrayList<>();
        for (Experiment e : experiments.findByTenantIdAndCompanyIdAndUpdatedAtGreaterThanEqualAndUpdatedAtLessThanOrderByUpdatedAtAsc(
                tenantId, companyId, from, to)) {
            // Both can happen in one period: decided between cycles, then measured at the next one.
            if (e.getMeasuredAt() != null && !e.getMeasuredAt().isBefore(from)) {
                measured.add(e.getResultSummary());
            }
            if (e.getDecidedAt() != null && !e.getDecidedAt().isBefore(from)) {
                decided.add((e.getStatus() == ExperimentStatus.REJECTED ? "You turned down" : "You approved")
                        + " experiment #" + e.getId() + " (" + label(e.getActionType()) + "): " + e.getHypothesis()
                        + (e.getDecisionNote() == null ? "" : " — \"" + e.getDecisionNote() + "\""));
            }
        }
        List<Awaiting> awaiting = new ArrayList<>();
        for (Experiment e : experiments.findByTenantIdAndCompanyIdAndStatusOrderByCreatedAtAsc(tenantId, companyId, ExperimentStatus.PROPOSED)) {
            if (e.getActionType() == ExperimentAction.DRAFT_SOCIAL_POST) continue; // its draft is already a review task
            Map<String, String> params = params(e);
            String title = e.getActionType() == ExperimentAction.SUGGESTION
                    ? "Suggestion: " + params.getOrDefault("suggestion", "")
                    : "Approve or turn down a change to GHL custom value \"" + params.getOrDefault("customValueName", "") + "\"";
            awaiting.add(new Awaiting("experiment:" + e.getId() + ":decide", clip(title, 240),
                    "Experiment #" + e.getId() + ": " + e.getHypothesis() + " CMO² → the customer's page → Experiments."));
        }
        return new RecordPart(decided, measured, awaiting);
    }

    // ---- the owner's decisions --------------------------------------------------------------

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST')")
    public List<ExperimentView> list(Long companyId, int limit) {
        var company = TenantGuard.owned(companies.findById(companyId))
                .orElseThrow(() -> ApiException.notFound("Company not found"));
        return experiments.findByTenantIdAndCompanyIdOrderByCreatedAtDesc(company.getTenantId(), companyId,
                PageRequest.of(0, Math.min(Math.max(limit, 1), MAX_LIST))).stream().map(this::view).toList();
    }

    @Transactional(noRollbackFor = GhlApiException.class)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ExperimentView approve(Long id, String note) {
        Experiment e = proposed(id);
        if (e.getActionType() == ExperimentAction.DRAFT_SOCIAL_POST) {
            throw ApiException.conflict("Approve and publish this experiment's draft in Approvals");
        }
        decide(e, note);
        if (e.getActionType() == ExperimentAction.UPDATE_CUSTOM_VALUE) {
            applyCustomValue(e, Actor.HUMAN, "experiment:" + e.getId());
        } else {
            e.setStatus(ExperimentStatus.APPLIED);
            e.setAppliedAt(Instant.now());
        }
        e = experiments.save(e);
        audit.record("EXPERIMENT_APPROVED", "Experiment", String.valueOf(id), e.getStatus().name());
        return view(e);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ExperimentView reject(Long id, String reason) {
        Experiment e = proposed(id);
        if (e.getActionType() == ExperimentAction.DRAFT_SOCIAL_POST) {
            throw ApiException.conflict("Reject this experiment's draft in Approvals");
        }
        decide(e, reason);
        e.setStatus(ExperimentStatus.REJECTED);
        e = experiments.save(e);
        audit.record("EXPERIMENT_REJECTED", "Experiment", String.valueOf(id), e.getDecisionNote());
        return view(e);
    }

    // ---- following a draft through Approvals ------------------------------------------------

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDraftRejected(Long tenantId, Long assetId, String reason) {
        experiments.findFirstByTenantIdAndAssetId(tenantId, assetId)
                .filter(e -> e.getStatus() == ExperimentStatus.PROPOSED)
                .ifPresent(e -> {
                    e.setStatus(ExperimentStatus.REJECTED);
                    e.setDecidedAt(Instant.now());
                    e.setDecisionNote(clip("Draft rejected" + (reason == null ? "" : ": " + reason), 1000));
                    experiments.save(e);
                });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDraftPublished(Long tenantId, Long assetId, Instant goesOutAt) {
        experiments.findFirstByTenantIdAndAssetId(tenantId, assetId)
                .filter(e -> e.getStatus() == ExperimentStatus.PROPOSED)
                .ifPresent(e -> {
                    e.setStatus(ExperimentStatus.APPLIED);
                    e.setDecidedAt(Instant.now());
                    e.setAppliedAt(goesOutAt);
                    experiments.save(e);
                });
    }

    // ---- helpers ---------------------------------------------------------------------------

    private void applyCustomValue(Experiment e, Actor actor, String approvalRef) {
        Map<String, String> params = params(e);
        try {
            customValues.update(e.getCompanyId(), params.get("customValueId"), params.get("newValue"), actor, approvalRef);
            e.setStatus(ExperimentStatus.APPLIED);
            e.setAppliedAt(Instant.now());
        } catch (GhlApiException ex) {
            e.setStatus(ExperimentStatus.FAILED);
            e.setResultSummary(clip("GoHighLevel refused the change: " + ex.getMessage(), 1000));
        }
        experiments.save(e);
    }

    private void decide(Experiment e, String note) {
        e.setDecidedBy(TenantContext.getUserId());
        e.setDecidedAt(Instant.now());
        e.setDecisionNote(note == null || note.isBlank() ? null : clip(note.strip(), 1000));
    }

    private Experiment proposed(Long id) {
        Experiment e = TenantGuard.owned(experiments.findById(id))
                .orElseThrow(() -> ApiException.notFound("Experiment not found"));
        if (e.getStatus() != ExperimentStatus.PROPOSED) {
            throw ApiException.conflict("This experiment was already decided (it's " + e.getStatus() + ")");
        }
        return e;
    }

    private ExperimentView view(Experiment e) {
        return new ExperimentView(e.getId(), e.getIterationId(), e.getHypothesis(), e.getActionType().name(), e.getRiskTier(),
                e.getStatus().name(), e.getMetric(), Metrics.label(e.getMetric()), e.getExpectedDirection(), params(e),
                e.getBaselineValue(), e.getResultValue(), e.getOutcome(), e.getResultSummary(), e.getAssetId(),
                e.getDecisionNote(), e.getCreatedAt(), e.getAppliedAt(), e.getMeasuredAt());
    }

    private Map<String, String> params(Experiment e) {
        try {
            if (e.getParamsJson() != null) return json.readValue(e.getParamsJson(), new TypeReference<>() {});
        } catch (Exception ignored) {
            // shown without parameters
        }
        return Map.of();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String label(ExperimentAction a) {
        return a.name().replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private static String num(double v) {
        return v == Math.rint(v) ? String.format(Locale.ROOT, "%,d", (long) v) : String.format(Locale.ROOT, "%,.1f", v);
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
