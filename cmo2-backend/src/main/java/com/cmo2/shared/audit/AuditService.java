package com.cmo2.shared.audit;

import com.cmo2.shared.security.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes immutable audit entries. Tenant/actor default from {@link TenantContext}
 * when not supplied explicitly. Runs in its own transaction so audit always records,
 * even if the surrounding business transaction rolls back.
 */
@Service
public class AuditService {

    private final AuditEntryRepository repository;

    public AuditService(AuditEntryRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String entity, String entityId, String detail) {
        AuditEntry e = new AuditEntry();
        e.setTenantId(TenantContext.getTenantId());
        e.setActorId(TenantContext.getUserId());
        e.setAction(action);
        e.setEntity(entity);
        e.setEntityId(entityId);
        e.setDetail(detail);
        repository.save(e);
    }

    /** Variant for pre-authentication events (registration) where context is not yet bound. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFor(Long tenantId, Long actorId, String action, String entity, String entityId, String detail) {
        AuditEntry e = new AuditEntry();
        e.setTenantId(tenantId);
        e.setActorId(actorId);
        e.setAction(action);
        e.setEntity(entity);
        e.setEntityId(entityId);
        e.setDetail(detail);
        repository.save(e);
    }
}
