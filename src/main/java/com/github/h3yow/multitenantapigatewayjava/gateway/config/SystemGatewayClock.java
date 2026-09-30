package com.github.h3yow.multitenantapigatewayjava.gateway.config;

import java.time.Instant;

public class SystemGatewayClock implements GatewayClock {
    @Override
    public Instant now() {
        return Instant.now();
    }

    @Override
    public long nowEpochMillis() {
        return System.currentTimeMillis();
    }
}
