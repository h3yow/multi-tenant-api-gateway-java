package com.github.h3yow.multitenantapigatewayjava.web;

/** 统一错误响应体。 */
public record ErrorResponse(long timestamp, int status, String error, String message, String path) {
}
