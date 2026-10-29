package com.cmo2.strangeloop.stages;

import com.cmo2.shared.security.TenantContext;
import com.cmo2.strangeloop.domain.StageName;
import com.cmo2.strangeloop.memory.CycleMemoryService;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Stage 3 — Documentation, and the cycle's Close: builds this cycle's record (decisions, tasks done
 * and pending, content produced, prompts and responses), writes it to the customer's own Notion and
 * Confluence pages, keeps their Linear project in step and posts to Slack, via
 * {@link CycleMemoryService}. With no tools connected the record is still kept in CMO².
 */
@Service
public class Stage3Documentation implements LoopStage {

    private final CycleMemoryService memory;

    public Stage3Documentation(CycleMemoryService memory) {
        this.memory = memory;
    }

    @Override
    public StageName name() {
        return StageName.DOCUMENTATION;
    }

    @Override
    public Map<String, Object> run(StageContext context) {
        return memory.close(TenantContext.requireTenantId(), context.companyId(), context.iterationId(),
                context.iterationNo(), context);
    }
}
