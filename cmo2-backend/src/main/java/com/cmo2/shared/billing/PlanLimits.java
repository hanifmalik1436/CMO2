package com.cmo2.shared.billing;

/**
 * Port for plan-quota enforcement, exposed from the shared kernel so feature modules
 * (companies, integrations, team) can enforce limits without depending on the billing
 * module — keeping module dependencies acyclic. Implemented by
 * {@code com.cmo2.billing.service.PlanLimitGuard}.
 *
 * <p>Each method throws {@link com.cmo2.shared.exceptions.PlanLimitException} (HTTP 402)
 * when the current tenant's quota is reached.
 */
public interface PlanLimits {

    void assertCanAddCompany();

    void assertCanAddConnection();

    void assertCanAddSeat();
}
