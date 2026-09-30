package com.github.h3yow.multitenantapigatewayjava.gateway.audit;

import java.util.List;

public interface AuditService {

    void record(AuditEvent event);

    List<AuditEvent> findByTenant(String tenantId);

    List<AuditEvent> all();
}
