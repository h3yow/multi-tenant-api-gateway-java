package com.github.h3yow.multitenantapigatewayjava.gateway.ratelimit;

public interface RateLimitService {

    RateLimitDecision checkAndConsume(String tenantId);
}
