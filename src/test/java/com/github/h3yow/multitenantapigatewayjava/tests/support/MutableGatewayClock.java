package com.github.h3yow.multitenantapigatewayjava.tests.support;

import com.github.h3yow.multitenantapigatewayjava.gateway.config.GatewayClock;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试时钟：初始固定在一个已知时刻，可手动前进，用于精确复现固定窗口刷新。
 */
public class MutableGatewayClock implements GatewayClock {

    private final AtomicLong millis;

    public MutableGatewayClock(long startEpochMillis) {
        this.millis = new AtomicLong(startEpochMillis);
    }

    @Override
    public Instant now() {
        return Instant.ofEpochMilli(millis.get());
    }

    @Override
    public long nowEpochMillis() {
        return millis.get();
    }

    public void setMillis(long value) {
        millis.set(value);
    }

    public void advanceMillis(long delta) {
        millis.addAndGet(delta);
    }
}
