package com.github.h3yow.multitenantapigatewayjava.idempotency;

/**
 * 首次请求完成后缓存的响应。
 *
 * @param status HTTP 状态码
 * @param body   响应字节
 */
public record CachedResponse(int status, byte[] body) {
}
