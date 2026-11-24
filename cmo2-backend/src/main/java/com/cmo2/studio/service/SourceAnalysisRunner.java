package com.cmo2.studio.service;

import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.studio.domain.ContentSource;
import com.cmo2.studio.repository.ContentSourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Finds a new source's ideas in the background, so the owner can add a source and move on: the
 * Content Library shows "Finding ideas…" until it's done. Text too short to hold an idea is marked
 * TOO_SHORT without an AI call; drafts can still be written from the full source.
 */
@Component
public class SourceAnalysisRunner {

    private static final Logger log = LoggerFactory.getLogger(SourceAnalysisRunner.class);
    static final int DEFAULT_COUNT = 8;

    private final ContentSourceRepository sources;
    private final IdeaFinder finder;
    private final TaskExecutor executor;
    private final int minChars;

    public SourceAnalysisRunner(ContentSourceRepository sources, IdeaFinder finder,
                                @Qualifier("studioTaskExecutor") TaskExecutor executor,
                                @Value("${cmo2.studio.min-analyze-chars:300}") int minChars) {
        this.sources = sources;
        this.finder = finder;
        this.executor = executor;
        this.minChars = minChars;
    }

    public void submit(Long tenantId, Long sourceId) {
        executor.execute(() -> run(tenantId, sourceId));
    }

    void run(Long tenantId, Long sourceId) {
        TenantContext.set(tenantId, null);
        try {
            ContentSource s = sources.findById(sourceId).filter(x -> tenantId.equals(x.getTenantId())).orElse(null);
            if (s == null) return;
            if (s.getBody() == null || s.getBody().strip().length() < minChars) {
                finder.mark(tenantId, sourceId, ContentSource.TOO_SHORT,
                        "Too short to find ideas in; you can still write from the full text");
                return;
            }
            finder.mark(tenantId, sourceId, ContentSource.ANALYZING, null);
            JsonNode answer = finder.ask(s, DEFAULT_COUNT);
            finder.save(tenantId, sourceId, answer);
        } catch (ApiException e) {
            log.warn("Finding ideas in source {} failed: {}", sourceId, e.getMessage());
            finder.mark(tenantId, sourceId, ContentSource.FAILED, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Finding ideas in source {} failed", sourceId, e);
            finder.mark(tenantId, sourceId, ContentSource.FAILED, "Something went wrong finding ideas; try Re-analyze");
        } finally {
            TenantContext.clear();
        }
    }
}
