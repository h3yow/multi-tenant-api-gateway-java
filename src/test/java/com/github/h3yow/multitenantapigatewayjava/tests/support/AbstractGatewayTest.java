package com.github.h3yow.multitenantapigatewayjava.tests.support;

import com.github.h3yow.multitenantapigatewayjava.gateway.ratelimit.FixedWindowRateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 所有网关测试共用的 SpringBoot + MockMvc 基座。
 * 每个用例前重置时钟与限流窗口，保证租户配额不会跨用例污染、窗口刷新结果可复现。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(GatewayTestConfig.class)
public abstract class AbstractGatewayTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected MutableGatewayClock clock;

    @Autowired
    protected FixedWindowRateLimitService rateLimitService;

    @BeforeEach
    void resetClockAndWindows() {
        clock.setMillis(GatewayTestConfig.ALIGNED_START);
        rateLimitService.resetAllForTesting();
    }
}
