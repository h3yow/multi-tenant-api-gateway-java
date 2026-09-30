package com.github.h3yow.multitenantapigatewayjava.api.dto;

/** 订单响应体。 */
public record OrderResponse(String id, String name, String content, boolean scoped) {
}
