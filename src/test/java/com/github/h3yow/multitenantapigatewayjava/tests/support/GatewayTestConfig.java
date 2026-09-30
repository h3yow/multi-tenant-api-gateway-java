package com.github.h3yow.multitenantapigatewayjava.tests.support;

import com.github.h3yow.multitenantapigatewayjava.gateway.config.GatewayClock;
import com.github.h3yow.multitenantapigatewayjava.gateway.ratelimit.FixedWindowRateLimitService;
import com.github.h3yow.multitenantapigatewayjava.gateway.resource.ResourceRepository;
import com.github.h3yow.multitenantapigatewayjava.gateway.security.InMemoryTenantDirectory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试专用装配：可变时钟 + 测试租户辅助。仅被需要的测试通过 @Import 引入，
 * 不污染普通上下文加载测试（如 ApplicationTests）。
 */
@TestConfiguration
public class GatewayTestConfig {

    public static final long START_MILLIS = 1_790_000_000_000L;
    public static final long ALIGNED_START = (START_MILLIS / 60_000L) * 60_000L;

    @Bean
    @Primary
    public MutableGatewayClock mutableGatewayClock() {
        return new MutableGatewayClock(ALIGNED_START);
    }

    @Bean
    public TestTenants testTenants(InMemoryTenantDirectory directory,
                                   ResourceRepository resources,
                                   MutableGatewayClock clock) {
        return new TestTenants(directory, resources, clock);
    }
}
