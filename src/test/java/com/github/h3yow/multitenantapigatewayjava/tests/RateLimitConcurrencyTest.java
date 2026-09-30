package com.github.h3yow.multitenantapigatewayjava.tests;

import com.github.h3yow.multitenantapigatewayjava.tests.support.AbstractGatewayTest;
import com.github.h3yow.multitenantapigatewayjava.tests.support.TestTenants;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 高并发固定窗口限流：断言"既不少算也不多算"——配额为 N 时，
 * 远多于 N 的并发请求中恰好 N 个 200，其余全部 429。
 */
class RateLimitConcurrencyTest extends AbstractGatewayTest {

    private static final Logger log = LoggerFactory.getLogger("TEST-RATELIMIT");

    @Autowired
    private TestTenants testTenants;

    @Test
    void exactlyQuotaRequestsPassUnderHighConcurrency() throws Exception {
        long quota = 20L;
        int threads = 100;
        String resourceId = testTenants.registerTenantWithKeyAndResource("tenant-concurrent", "concurrent-key", quota, "res-concurrent");
        String key = "concurrent-key";

        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        log.info("[计数过程] 开始 租户=tenant-concurrent 配额={} 并发线程={} 窗口起点={}",
                quota, threads, clock.nowEpochMillis());

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await(); // 让所有线程尽量同时发起，制造真实竞争。
                    int status = mockMvc.perform(get("/api/resources/" + resourceId).header("X-Api-Key", key))
                            .andReturn().getResponse().getStatus();
                    if (status == 200) {
                        allowed.incrementAndGet();
                    } else if (status == 429) {
                        rejected.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                    }
                } catch (Exception e) {
                    other.incrementAndGet();
                    log.error("[计数过程] 请求异常", e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发请求应在超时前全部结束");
        pool.shutdown();

        log.info("[计数过程] 结束 租户=tenant-concurrent 放行={} 拒绝={} 其它={} (配额={})",
                allowed.get(), rejected.get(), other.get(), quota);

        assertEquals(0, other.get(), "不应出现 200/429 以外的状态");
        assertEquals(quota, allowed.get(), "放行数必须恰好等于配额，不能多算（超发）");
        assertEquals(threads - quota, rejected.get(), "被拒数必须恰好等于 总数-配额，不能少算");
    }

    @Test
    void quotaRefreshesAtNextAlignedWindow() throws Exception {
        long quota = 2L;
        String resourceId = testTenants.registerTenantWithKeyAndResource("tenant-window", "window-key", quota, "res-window");
        String key = "window-key";

        // 第一个窗口：恰好 quota 个放行。
        for (int i = 1; i <= quota; i++) {
            int remaining = (int) (quota - i);
            log.info("[计数过程] 窗口1 租户=tenant-window 第{}次 期望放行 remaining={} resetAt={}",
                    i, remaining, (clock.nowEpochMillis() / 60000) * 60000 + 60000);
            mockMvc.perform(get("/api/resources/" + resourceId).header("X-Api-Key", key))
                    .andExpect(result -> assertEquals(200, result.getResponse().getStatus()))
                    .andExpect(result -> assertEquals(String.valueOf(remaining),
                            result.getResponse().getHeader("X-RateLimit-Remaining")));
        }
        log.info("[计数过程] 窗口1 第3次 期望拒绝429 remaining=0");
        mockMvc.perform(get("/api/resources/" + resourceId).header("X-Api-Key", key))
                .andExpect(result -> assertEquals(429, result.getResponse().getStatus()))
                .andExpect(result -> assertEquals("0", result.getResponse().getHeader("X-RateLimit-Remaining")));

        long firstReset = Long.parseLong(
                mockMvc.perform(get("/api/resources/" + resourceId).header("X-Api-Key", key))
                        .andReturn().getResponse().getHeader("X-RateLimit-Reset"));

        // 推进到下一个对齐窗口边界之后，配额必须完整恢复，且 reset 点正好是再下一个边界。
        clock.setMillis(firstReset);
        log.info("[计数过程] 推进时钟到窗口刷新点 resetAt={}", firstReset);
        mockMvc.perform(get("/api/resources/" + resourceId).header("X-Api-Key", key))
                .andExpect(result -> assertEquals(200, result.getResponse().getStatus()))
                .andExpect(result -> assertEquals(String.valueOf(quota - 1),
                        result.getResponse().getHeader("X-RateLimit-Remaining")))
                .andExpect(result -> assertEquals(String.valueOf(firstReset + 60000),
                        result.getResponse().getHeader("X-RateLimit-Reset")));
        log.info("[计数过程] 窗口2 刷新后放行成功，新resetAt={}", firstReset + 60000);
    }
}
