package com.github.h3yow.multitenantapigatewayjava.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    /** 固定限流窗口长度（毫秒）。窗口按 epoch 对齐。 */
    private long rateLimitWindowMillis = 60_000L;

    public long getRateLimitWindowMillis() {
        return rateLimitWindowMillis;
    }

    public void setRateLimitWindowMillis(long rateLimitWindowMillis) {
        this.rateLimitWindowMillis = rateLimitWindowMillis;
    }
}
