package com.github.h3yow.multitenantapigatewayjava.audit;

/** 被审计的判定动作。 */
public enum AuditAction {
    AUTH_PASS,
    AUTH_DENY,
    RATE_LIMIT_PASS,
    RATE_LIMIT_DENY,
    IDEMPOTENCY_REPLAY,
    IDEMPOTENCY_CONFLICT,
    REQUEST_OK,
    REQUEST_DENIED,
    REQUEST_FAILED
}
