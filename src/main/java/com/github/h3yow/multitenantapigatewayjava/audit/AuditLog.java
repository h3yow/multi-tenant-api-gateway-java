package com.github.h3yow.multitenantapigatewayjava.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 审计落地点：内存留存 + 日志输出。
 * 任何路径（成功、业务失败、认证/限流/幂等拒绝、服务端异常）都必须经过这里。
 */
@Component
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    private final CopyOnWriteArrayList<AuditEvent> events = new CopyOnWriteArrayList<>();

    public AuditLog() {
    }

    public void record(AuditEvent event) {
        events.add(event);
        log.info("audit tenant={} keyId={} {} {} action={} status={} detail={}",
                event.tenantId(),
                event.keyId(),
                event.method(),
                event.path(),
                event.action(),
                event.httpStatus(),
                event.detail());
    }

    public List<AuditEvent> events() {
        return List.copyOf(events);
    }

    public List<AuditEvent> eventsForTenant(String tenantId) {
        return events.stream().filter(e -> tenantId.equals(e.tenantId())).toList();
    }

    public void clear() {
        events.clear();
    }
}
