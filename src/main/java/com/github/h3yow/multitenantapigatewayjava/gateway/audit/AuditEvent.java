package com.github.h3yow.multitenantapigatewayjava.gateway.audit;

import java.time.Instant;

/**
 * 一条审计记录。成功与所有拒绝路径（认证/鉴权/限流/幂等冲突）都必须落一条。
 */
public record AuditEvent(Instant timestamp,
                         String tenantId,
                         String apiKey,
                         String role,
                         String method,
                         String path,
                         int httpStatus,
                         String decision,
                         String reason,
                         String idempotencyKey,
                         boolean replay) {
}
