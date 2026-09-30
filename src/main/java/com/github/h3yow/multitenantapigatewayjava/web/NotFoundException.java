package com.github.h3yow.multitenantapigatewayjava.web;

/** 资源不存在，或存在但调用方无权感知（两种情况同一响应，避免存在性泄漏）。 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
