package com.github.h3yow.multitenantapigatewayjava.tests;

import com.github.h3yow.multitenantapigatewayjava.audit.AuditLog;
import com.github.h3yow.multitenantapigatewayjava.idempotency.IdempotencyService;
import com.github.h3yow.multitenantapigatewayjava.ratelimit.RateLimitService;
import com.github.h3yow.multitenantapigatewayjava.store.DataStore;
import com.github.h3yow.multitenantapigatewayjava.tests.support.MutableClock;
import com.github.h3yow.multitenantapigatewayjava.tests.support.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 集成测试基类：注入 MockMvc，并在每个用例前把内存数据、限流窗口、
 * 幂等槽位、审计事件全部重置为固定初始状态，保证用例间互不干扰、结果可复现。
 * 时钟使用 {@link MutableClock}，默认对齐到整分钟窗口边界，需要时可手动前进。
 */
@SpringBootTest(classes = {
        com.github.h3yow.multitenantapigatewayjava.Application.class,
        TestClockConfig.class
})
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected DataStore dataStore;

    @Autowired
    protected RateLimitService rateLimitService;

    @Autowired
    protected IdempotencyService idempotencyService;

    @Autowired
    protected AuditLog auditLog;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetAllState() {
        dataStore.reset();
        rateLimitService.reset();
        idempotencyService.reset();
        auditLog.clear();
        // 对齐到“下一个”固定窗口起始 + 5 秒缓冲，避免重置后、用例执行前恰好跨过窗口边界造成抖动。
        long now = System.currentTimeMillis();
        long nextWindowStart = (now / RateLimitService.WINDOW_MILLIS + 1) * RateLimitService.WINDOW_MILLIS;
        clock.setMillis(nextWindowStart + 5_000L);
    }
}
