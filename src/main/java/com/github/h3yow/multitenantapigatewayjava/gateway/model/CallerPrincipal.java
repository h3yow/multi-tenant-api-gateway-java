package com.github.h3yow.multitenantapigatewayjava.gateway.model;

/**
 * 认证通过后的调用方身份，沿过滤器链向下传递。
 */
public record CallerPrincipal(String tenantId, String apiKey, Role role) {
}
