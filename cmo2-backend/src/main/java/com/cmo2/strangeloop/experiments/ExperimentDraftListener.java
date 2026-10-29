package com.cmo2.strangeloop.experiments;

import com.cmo2.assets.events.AssetPublishedEvent;
import com.cmo2.assets.events.AssetReviewedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Keeps an experiment in step with its draft: rejected → rejected, published → applied. */
@Component
public class ExperimentDraftListener {

    private static final Logger log = LoggerFactory.getLogger(ExperimentDraftListener.class);

    private final ExperimentService experiments;

    public ExperimentDraftListener(ExperimentService experiments) {
        this.experiments = experiments;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onReviewed(AssetReviewedEvent event) {
        if (event.approved()) return; // approval alone doesn't apply it; publishing does
        try {
            experiments.onDraftRejected(event.tenantId(), event.assetId(), event.note());
        } catch (RuntimeException e) {
            log.warn("Could not update the experiment for draft {}: {}", event.assetId(), e.getMessage());
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onPublished(AssetPublishedEvent event) {
        try {
            experiments.onDraftPublished(event.tenantId(), event.assetId(), event.goesOutAt());
        } catch (RuntimeException e) {
            log.warn("Could not update the experiment for draft {}: {}", event.assetId(), e.getMessage());
        }
    }
}
