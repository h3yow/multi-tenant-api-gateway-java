package com.github.h3yow.multitenantapigatewayjava.domain;

/** 认证通过后绑定到请求上的调用方身份。 */
public record Principal(String tenantId, String keyId, TenantRole role) {
}
