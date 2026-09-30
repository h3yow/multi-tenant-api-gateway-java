package com.github.h3yow.multitenantapigatewayjava.gateway.resource;

/**
 * 受保护的租户资源。ownerTenant 决定它属于哪个租户。
 */
public record ResourceRecord(String id, String ownerTenant, String name, String content) {
}
