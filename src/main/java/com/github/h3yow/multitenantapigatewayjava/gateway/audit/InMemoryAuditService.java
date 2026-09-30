package com.github.h3yow.multitenantapigatewayjava.gateway.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存审计留存。每条事件同时写结构化日志，便于在测试输出中追溯
 * "租户 / 判定依据 / 结果"。生产环境应替换为持久化实现。
 */
@Service
public class InMemoryAuditService implements AuditService {

    private static final Logger log = LoggerFactory.getLogger(InMemoryAuditService.class);

    private final CopyOnWriteArrayList<AuditEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void record(AuditEvent event) {
        events.add(event);
        log.info("audit tenant={} role={} keyTail={} {} {} status={} decision={} reason={} idempotencyKey={} replay={}",
                event.tenantId(),
                event.role(),
                tail(event.apiKey()),
                event.method(),
                event.path(),
                event.httpStatus(),
                event.decision(),
                event.reason(),
                event.idempotencyKey(),
                event.replay());
    }

    @Override
    public List<AuditEvent> findByTenant(String tenantId) {
        return events.stream().filter(e -> tenantId.equals(e.tenantId())).toList();
    }

    @Override
    public List<AuditEvent> all() {
        return List.copyOf(events);
    }

    private static String tail(String key) {
        if (key == null) {
            return "-";
        }
        return key.length() <= 4 ? "****" : "****" + key.substring(key.length() - 4);
    }
}
