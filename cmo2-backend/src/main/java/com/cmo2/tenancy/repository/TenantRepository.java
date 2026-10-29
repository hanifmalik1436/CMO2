package com.cmo2.tenancy.repository;

import com.cmo2.tenancy.domain.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, Long> {

    // Webhook handlers run with no tenant context; they resolve the tenant from Stripe ids.
    Optional<Tenant> findByStripeCustomerId(String stripeCustomerId);

    Optional<Tenant> findByStripeSubscriptionId(String stripeSubscriptionId);
}
