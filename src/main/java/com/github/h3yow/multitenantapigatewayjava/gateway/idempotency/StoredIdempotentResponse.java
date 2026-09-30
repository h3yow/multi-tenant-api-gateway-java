package com.github.h3yow.multitenantapigatewayjava.gateway.idempotency;

/**
 * 首次请求完成后保存下来的响应快照，供重放请求返回完全一致的结果。
 */
public record StoredIdempotentResponse(int status, String contentType, byte[] body) {
}
