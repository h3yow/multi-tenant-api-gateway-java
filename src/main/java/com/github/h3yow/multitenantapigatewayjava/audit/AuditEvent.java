package com.github.h3yow.multitenantapigatewayjava.audit;

/**
 * 一条可追溯的审计记录。成功、失败、拒绝路径都必须落一条。
 */
public record AuditEvent(long timestamp,
                         String tenantId,
                         String keyId,
                         String method,
                         String path,
                         AuditAction action,
                         int httpStatus,
                         String detail) {
}
