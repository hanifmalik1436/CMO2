package com.cmo2.strangeloop.schedule;

import com.cmo2.strangeloop.domain.Cadence;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.TimeZone;

/**
 * The Quartz side of a customer's schedule: one job and one cron trigger per customer, in the
 * customer's timezone. A run missed while CMO² was down fires once when it comes back.
 */
@Component
public class LoopQuartz {

    static final String GROUP = "strange-loop";
    static final String TENANT_ID = "tenantId";
    static final String COMPANY_ID = "companyId";

    private final Scheduler scheduler;

    public LoopQuartz(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    /** Quartz cron (seconds first; day-of-week and day-of-month can't both be set, so one is "?"). */
    public static String cron(Cadence cadence, LocalTime at, Integer dayOfWeek, Integer dayOfMonth) {
        String time = "0 " + at.getMinute() + " " + at.getHour() + " ";
        return switch (cadence) {
            case DAILY -> time + "* * ?";
            case WEEKLY -> time + "? * " + DayOfWeek.of(dayOfWeek).name().substring(0, 3);
            case MONTHLY -> time + dayOfMonth + " * ?";
        };
    }

    public void schedule(Long tenantId, Long companyId, String cron, ZoneId zone) {
        JobKey jobKey = jobKey(companyId);
        TriggerKey triggerKey = triggerKey(companyId);
        try {
            if (matches(companyId, cron, zone)) {
                return;
            }
            JobDetail job = JobBuilder.newJob(LoopCycleJob.class)
                    .withIdentity(jobKey)
                    .usingJobData(TENANT_ID, tenantId)
                    .usingJobData(COMPANY_ID, companyId)
                    .storeDurably()
                    .build();
            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(triggerKey)
                    .forJob(jobKey)
                    .withSchedule(CronScheduleBuilder.cronSchedule(cron)
                            .inTimeZone(TimeZone.getTimeZone(zone))
                            .withMisfireHandlingInstructionFireAndProceed())
                    .build();
            scheduler.addJob(job, true);
            if (scheduler.checkExists(triggerKey)) {
                scheduler.rescheduleJob(triggerKey, trigger);
            } else {
                scheduler.scheduleJob(trigger);
            }
        } catch (SchedulerException e) {
            throw new IllegalStateException("Could not schedule the Strange Loop for customer " + companyId, e);
        }
    }

    public void unschedule(Long companyId) {
        try {
            scheduler.deleteJob(jobKey(companyId));
        } catch (SchedulerException e) {
            throw new IllegalStateException("Could not remove the Strange Loop schedule for customer " + companyId, e);
        }
    }

    public Optional<Instant> nextRun(Long companyId) {
        try {
            Trigger t = scheduler.getTrigger(triggerKey(companyId));
            return t == null || t.getNextFireTime() == null ? Optional.empty() : Optional.of(t.getNextFireTime().toInstant());
        } catch (SchedulerException e) {
            return Optional.empty();
        }
    }

    /** True when the customer already has exactly this cron in this timezone. */
    boolean matches(Long companyId, String cron, ZoneId zone) throws SchedulerException {
        Trigger t = scheduler.getTrigger(triggerKey(companyId));
        return t instanceof CronTrigger c && cron.equals(c.getCronExpression())
                && zone.getId().equals(c.getTimeZone().getID());
    }

    static JobKey jobKey(Long companyId) {
        return JobKey.jobKey("company-" + companyId, GROUP);
    }

    static TriggerKey triggerKey(Long companyId) {
        return TriggerKey.triggerKey("company-" + companyId, GROUP);
    }
}
