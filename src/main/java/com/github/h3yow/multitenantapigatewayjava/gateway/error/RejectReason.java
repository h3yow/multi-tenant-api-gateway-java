package com.github.h3yow.multitenantapigatewayjava.gateway.error;

/**
 * 拒绝原因码。认证、鉴权、限流、幂等冲突各占独立码段，保证拒绝结果可解释。
 */
public enum RejectReason {

    // ---- 认证 401 ----
    MISSING_API_KEY("missing_api_key"),
    UNKNOWN_API_KEY("unknown_api_key"),
    API_KEY_EXPIRED("api_key_expired"),
    API_KEY_REVOKED("api_key_revoked"),

    // ---- 鉴权 403/404 ----
    FORBIDDEN_ROLE("forbidden_role"),

    // ---- 限流 429 ----
    RATE_LIMIT_EXCEEDED("rate_limit_exceeded"),

    // ---- 幂等冲突 409 ----
    IDEMPOTENCY_KEY_IN_USE("idempotency_key_in_use"),
    IDEMPOTENCY_KEY_CONFLICT("idempotency_key_conflict");

    private final String code;

    RejectReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
