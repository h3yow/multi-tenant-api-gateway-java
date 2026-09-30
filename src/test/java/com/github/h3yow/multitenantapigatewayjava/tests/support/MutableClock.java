package com.github.h3yow.multitenantapigatewayjava.tests.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 测试用可控时钟：初始等于真实 UTC 时间，可手动前进以复现固定窗口切换。 */
public class MutableClock extends Clock {

    private volatile long millis = System.currentTimeMillis();
    private final ZoneId zone = ZoneOffset.UTC;

    public void advanceMillis(long delta) {
        this.millis += delta;
    }

    public void setMillis(long millis) {
        this.millis = millis;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis);
    }
}
