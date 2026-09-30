package com.github.h3yow.multitenantapigatewayjava.gateway.security;

import com.github.h3yow.multitenantapigatewayjava.gateway.error.RejectReason;
import com.github.h3yow.multitenantapigatewayjava.gateway.model.CallerPrincipal;

/**
 * 认证结果：成功携带身份，失败携带细分拒绝原因。
 */
public sealed interface AuthResult permits AuthResult.Ok, AuthResult.Reject {

    record Ok(CallerPrincipal principal) implements AuthResult {
    }

    record Reject(RejectReason reason, String detail) implements AuthResult {
    }
}
