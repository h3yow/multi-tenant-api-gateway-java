package com.github.h3yow.multitenantapigatewayjava.gateway.config;

import java.time.Instant;

/**
 * 可替换时钟。生产实现读系统时间；测试里可手动推进，复现窗口刷新语义。
 */
public interface GatewayClock {

    Instant now();

    long nowEpochMillis();
}
