package com.github.h3yow.multitenantapigatewayjava.auth;

/** 认证被拒绝的明确原因，三种 key 问题各自独立。 */
public enum AuthReason {
    MISSING_KEY,
    INVALID_KEY,
    EXPIRED_KEY,
    REVOKED_KEY
}
