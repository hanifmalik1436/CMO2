package com.cmo2.integrations.events;

/** A GHL sub-account was enabled as a customer (a company). Listeners set up its workspace pages. */
public record CustomerEnabledEvent(Long tenantId, Long companyId, String companyName) {}
