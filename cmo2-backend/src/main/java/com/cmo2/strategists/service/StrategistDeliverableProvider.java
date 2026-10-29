package com.cmo2.strategists.service;

import java.util.Optional;

/**
 * Read seam that lets the Strange Loop (Stage 1 Innovation) consume the latest
 * approved strategist deliverables for a company — without strangeloop depending on
 * the strategist internals. Dependency direction is strangeloop → strategists (acyclic).
 */
public interface StrategistDeliverableProvider {

    Optional<String> latestApprovedDeliverables(Long companyId);
}
