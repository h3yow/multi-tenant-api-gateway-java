package com.github.h3yow.multitenantapigatewayjava.gateway.model;

/**
 * 租户内角色。角色只在同一租户内决定可访问范围。
 */
public enum Role {
    ADMIN,
    WRITER,
    READER
}
