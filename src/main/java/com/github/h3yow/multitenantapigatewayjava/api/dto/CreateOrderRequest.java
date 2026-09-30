package com.github.h3yow.multitenantapigatewayjava.api.dto;

/** 创建订单请求体。 */
public record CreateOrderRequest(String name, String content, Boolean scoped) {
}
