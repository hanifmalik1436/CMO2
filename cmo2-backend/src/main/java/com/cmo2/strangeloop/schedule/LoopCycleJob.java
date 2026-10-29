package com.cmo2.strangeloop.schedule;

import com.cmo2.shared.security.TenantContext;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;

/** Fires one customer's scheduled cycle. Quartz creates it through Spring, so beans are injected. */
@DisallowConcurrentExecution
public class LoopCycleJob implements Job {

    private final LoopScheduleService schedules;

    public LoopCycleJob(LoopScheduleService schedules) {
        this.schedules = schedules;
    }

    @Override
    public void execute(JobExecutionContext context) {
        JobDataMap data = context.getMergedJobDataMap();
        long tenantId = data.getLong(LoopQuartz.TENANT_ID);
        long companyId = data.getLong(LoopQuartz.COMPANY_ID);
        // No request thread: bind the customer's tenant for the cycle it starts.
        TenantContext.set(tenantId, null);
        try {
            schedules.fire(tenantId, companyId);
        } finally {
            TenantContext.clear();
        }
    }
}
