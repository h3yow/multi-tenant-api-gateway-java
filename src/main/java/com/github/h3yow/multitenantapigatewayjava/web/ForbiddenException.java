package com.github.h3yow.multitenantapigatewayjava.web;

/** 通过认证但无权执行该端点级操作。 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
