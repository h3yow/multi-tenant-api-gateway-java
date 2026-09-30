package com.github.h3yow.multitenantapigatewayjava.gateway.model;

import java.time.Instant;

/**
 * 一把 API 密钥及其归属信息。状态通过 revoked / expiresAt 区分。
 */
public record ApiKey(String key, String tenantId, Role role, Instant expiresAt, boolean revoked) {
}
