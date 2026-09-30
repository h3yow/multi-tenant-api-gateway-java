package com.github.h3yow.multitenantapigatewayjava.auth;

/** 认证失败，携带可解释的拒绝原因。 */
public class AuthenticationException extends RuntimeException {

    private final AuthReason reason;

    public AuthenticationException(AuthReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public AuthReason reason() {
        return reason;
    }
}
