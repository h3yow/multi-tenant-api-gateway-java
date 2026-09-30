package com.github.h3yow.multitenantapigatewayjava.domain;

import java.time.Instant;

/**
 * 一个 API Key 的登记信息。
 *
 * @param keyId     key 的稳定标识（审计/日志用）
 * @param secret    key 明文（演示项目内存存储）
 * @param tenantId  所属租户
 * @param role      租户内角色
 * @param expiresAt 过期时刻
 * @param revoked   是否被主动吊销
 */
public record ApiKeyInfo(String keyId,
                         String secret,
                         String tenantId,
                         TenantRole role,
                         Instant expiresAt,
                         boolean revoked) {
}
