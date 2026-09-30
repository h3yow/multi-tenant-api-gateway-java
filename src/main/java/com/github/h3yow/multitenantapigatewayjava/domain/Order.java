package com.github.h3yow.multitenantapigatewayjava.domain;

/**
 * 演示资源：订单。
 *
 * @param scoped 为 true 表示受限资源（仅 ADMIN 可见），false 表示普通资源（VIEWER 也可见）
 */
public record Order(String id, String tenantId, String name, String content, boolean scoped) {
}
