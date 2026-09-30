package com.github.h3yow.multitenantapigatewayjava.gateway.model;

/**
 * 租户配置：租户标识 + 每窗口独立配额。
 */
public record Tenant(String id, long quotaPerWindow) {
}
