package com.cmo2.strangeloop.schedule;

import com.cmo2.integrations.core.Timezones;
import com.cmo2.shared.audit.AuditService;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.shared.tenancy.TenantGuard;
import com.cmo2.strangeloop.domain.Cadence;
import com.cmo2.strangeloop.domain.IterationStatus;
import com.cmo2.strangeloop.domain.LoopIteration;
import com.cmo2.strangeloop.domain.LoopTrigger;
import com.cmo2.strangeloop.domain.TriggerType;
import com.cmo2.strangeloop.dto.LoopDtos.ScheduleRequest;
import com.cmo2.strangeloop.dto.LoopDtos.ScheduleResponse;
import com.cmo2.strangeloop.orchestrator.LoopOrchestrator;
import com.cmo2.strangeloop.repository.LoopIterationRepository;
import com.cmo2.strangeloop.repository.LoopTriggerRepository;
import com.cmo2.tenancy.domain.Company;
import com.cmo2.tenancy.domain.CompanyStatus;
import com.cmo2.tenancy.events.CompanyTimezoneChangedEvent;
import com.cmo2.tenancy.repository.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Each customer's Strange Loop cadence: DAILY, WEEKLY or MONTHLY at a time of day in the customer's
 * own timezone. The {@code loop_triggers} row is the source of truth; Quartz holds the matching
 * trigger and is brought back in line at startup and whenever the customer's timezone changes.
 */
@Service
public class LoopScheduleService {

    private static final Logger log = LoggerFactory.getLogger(LoopScheduleService.class);
    static final String DEFAULT_TIME = "09:00";

    private final LoopTriggerRepository triggers;
    private final LoopIterationRepository iterations;
    private final CompanyRepository companies;
    private final LoopQuartz quartz;
    private final ObjectProvider<LoopOrchestrator> orchestrator; // lazy: the job runs long after startup
    private final AuditService audit;

    public LoopScheduleService(LoopTriggerRepository triggers, LoopIterationRepository iterations,
                               CompanyRepository companies, LoopQuartz quartz,
                               ObjectProvider<LoopOrchestrator> orchestrator, AuditService audit) {
        this.triggers = triggers;
        this.iterations = iterations;
        this.companies = companies;
        this.quartz = quartz;
        this.orchestrator = orchestrator;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public ScheduleResponse get(Long companyId) {
        Company company = owned(companyId);
        return toResponse(company, schedule(company.getTenantId(), companyId));
    }

    /** Every customer's schedule, for the Customers list. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public List<ScheduleResponse> list() {
        Long tenantId = TenantContext.requireTenantId();
        Map<Long, LoopTrigger> byCompany = triggers.findByTenantIdAndTriggerTypeAndActiveTrue(tenantId, LoopTrigger.SCHEDULE)
                .stream().collect(Collectors.toMap(LoopTrigger::getCompanyId, Function.identity(), (a, b) -> a));
        return companies.findAllByOrderByCreatedAtDesc().stream()
                .filter(c -> tenantId.equals(c.getTenantId()))
                .map(c -> toResponse(c, byCompany.get(c.getId())))
                .toList();
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ScheduleResponse set(Long companyId, ScheduleRequest req) {
        Company company = owned(companyId);
        Long tenantId = company.getTenantId();
        LoopTrigger t = triggers.findFirstByTenantIdAndCompanyIdAndTriggerType(tenantId, companyId, LoopTrigger.SCHEDULE)
                .orElse(null);

        if ("OFF".equals(req.cadence())) {
            if (t != null) {
                t.setActive(false);
                triggers.save(t);
            }
            quartz.unschedule(companyId);
            audit.record("LOOP_SCHEDULE_OFF", "Company", String.valueOf(companyId), company.getName());
            return toResponse(company, null);
        }

        Cadence cadence = Cadence.valueOf(req.cadence());
        if (t == null) {
            t = new LoopTrigger();
            t.setCompanyId(companyId);
            t.setTriggerType(LoopTrigger.SCHEDULE);
        }
        t.setCadence(cadence);
        t.setTimeOfDay(req.timeOfDay() == null || req.timeOfDay().isBlank() ? DEFAULT_TIME : req.timeOfDay());
        t.setDayOfWeek(cadence == Cadence.WEEKLY ? (req.dayOfWeek() == null ? 1 : req.dayOfWeek()) : null);
        t.setDayOfMonth(cadence == Cadence.MONTHLY ? (req.dayOfMonth() == null ? 1 : req.dayOfMonth()) : null);
        t.setActive(true);
        t = triggers.save(t);

        applyToQuartz(company, t);
        audit.record("LOOP_SCHEDULE_SET", "Company", String.valueOf(companyId),
                describe(t) + " (" + zone(company).getId() + ")");
        return toResponse(company, t);
    }

    /** Moves the customer's trigger after their timezone changed (internal; no principal needed). */
    @TransactionalEventListener(fallbackExecution = true)
    public void onTimezoneChanged(CompanyTimezoneChangedEvent event) {
        try {
            reschedule(event.tenantId(), event.companyId());
        } catch (RuntimeException e) {
            log.warn("Could not move the schedule of customer {} to {}: {}", event.companyId(), event.timezone(), e.getMessage());
        }
    }

    /** Makes Quartz match every active schedule row, e.g. after a restart or a lost trigger. */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileAll() {
        for (LoopTrigger t : triggers.findByTriggerTypeAndActiveTrue(LoopTrigger.SCHEDULE)) {
            try {
                reschedule(t.getTenantId(), t.getCompanyId());
            } catch (RuntimeException e) {
                log.warn("Could not restore the schedule of customer {}: {}", t.getCompanyId(), e.getMessage());
            }
        }
    }

    @Transactional(readOnly = true)
    public void reschedule(Long tenantId, Long companyId) {
        Company company = companies.findById(companyId).filter(c -> tenantId.equals(c.getTenantId())).orElse(null);
        LoopTrigger t = schedule(tenantId, companyId);
        if (company == null || t == null) {
            quartz.unschedule(companyId);
            return;
        }
        applyToQuartz(company, t);
    }

    /**
     * Called by {@link LoopCycleJob} with the tenant bound. Skips the run if the schedule was turned
     * off, the customer was archived, or a cycle for them is still running.
     */
    public void fire(Long tenantId, Long companyId) {
        Company company = companies.findById(companyId).filter(c -> tenantId.equals(c.getTenantId())).orElse(null);
        if (company == null || schedule(tenantId, companyId) == null) {
            quartz.unschedule(companyId);
            return;
        }
        if (company.getStatus() == CompanyStatus.ARCHIVED) {
            log.info("Skipping scheduled cycle: customer {} is archived", companyId);
            return;
        }
        if (iterations.existsByTenantIdAndCompanyIdAndStatus(tenantId, companyId, IterationStatus.RUNNING)) {
            log.info("Skipping scheduled cycle for customer {}: the previous one is still running", companyId);
            audit.record("LOOP_SCHEDULED_RUN_SKIPPED", "Company", String.valueOf(companyId), "previous cycle still running");
            return;
        }
        LoopIteration started = orchestrator.getObject().startCycle(companyId, TriggerType.SCHEDULED);
        audit.record("LOOP_CYCLE_STARTED", "LoopIteration", String.valueOf(started.getId()),
                "Scheduled run for company " + companyId);
    }

    private void applyToQuartz(Company company, LoopTrigger t) {
        String cron = LoopQuartz.cron(t.getCadence(), LocalTime.parse(t.getTimeOfDay()), t.getDayOfWeek(), t.getDayOfMonth());
        quartz.schedule(company.getTenantId(), company.getId(), cron, zone(company));
    }

    private LoopTrigger schedule(Long tenantId, Long companyId) {
        return triggers.findFirstByTenantIdAndCompanyIdAndTriggerType(tenantId, companyId, LoopTrigger.SCHEDULE)
                .filter(LoopTrigger::isActive)
                .orElse(null);
    }

    private ScheduleResponse toResponse(Company c, LoopTrigger t) {
        var last = iterations.findFirstByTenantIdAndCompanyIdAndTriggerTypeOrderByStartedAtDesc(
                c.getTenantId(), c.getId(), TriggerType.SCHEDULED).map(LoopIteration::getStartedAt).orElse(null);
        if (t == null) {
            return new ScheduleResponse(c.getId(), c.getName(), "OFF", null, null, null, zone(c).getId(), null, last);
        }
        return new ScheduleResponse(c.getId(), c.getName(), t.getCadence().name(), t.getTimeOfDay(), t.getDayOfWeek(),
                t.getDayOfMonth(), zone(c).getId(), quartz.nextRun(c.getId()).orElse(null), last);
    }

    private static ZoneId zone(Company c) {
        return Timezones.firstValid(c.getTimezone());
    }

    private static String describe(LoopTrigger t) {
        return switch (t.getCadence()) {
            case DAILY -> "daily at " + t.getTimeOfDay();
            case WEEKLY -> "weekly on day " + t.getDayOfWeek() + " at " + t.getTimeOfDay();
            case MONTHLY -> "monthly on the " + t.getDayOfMonth() + " at " + t.getTimeOfDay();
        };
    }

    private Company owned(Long companyId) {
        return TenantGuard.owned(companies.findById(companyId))
                .orElseThrow(() -> ApiException.notFound("Company not found"));
    }
}
