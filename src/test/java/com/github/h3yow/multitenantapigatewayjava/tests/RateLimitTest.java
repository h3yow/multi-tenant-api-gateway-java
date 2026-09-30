package com.github.h3yow.multitenantapigatewayjava.tests;

import com.github.h3yow.multitenantapigatewayjava.ratelimit.RateLimitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 限流测试：
 * 1) HTTP 串行：配额耗尽后第 6 个请求被 429 拒绝，且能看到剩余额度与刷新时刻；
 * 2) 服务层高并发：200 个线程同时抢 100 个额度，允许数必须恰好为 100（不少算不多算）；
 * 3) 租户间窗口互不影响。
 * 单测日志中会打印租户、判定依据与计数过程（count x->y / remaining）。
 */
class RateLimitTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RateLimitTest.class);

    private ExecutorService pool;

    @AfterEach
    void shutdownPool() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("串行：tenant-a 窗口配额 5，前 5 个放行，第 6 个 429 且 remaining=0")
    void quotaExhaustedSequentially() throws Exception {
        for (int i = 1; i <= 5; i++) {
            int expectedRemaining = 5 - i;
            log.info("[test] tenant=tenant-a request#{} expecting ALLOW count={}->{} remaining={}",
                    i, i - 1, i, expectedRemaining);
            mockMvc.perform(get("/api/orders").header("X-Api-Key", "secret-tenant-a-viewer"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Limit", "5"))
                    .andExpect(header().string("X-RateLimit-Remaining", Integer.toString(expectedRemaining)));
        }

        log.info("[test] tenant=tenant-a request#6 expecting DENY basis=count=5>=limit=5 remaining=0");
        mockMvc.perform(get("/api/orders").header("X-Api-Key", "secret-tenant-a-viewer"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-RateLimit-Limit", "5"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().exists("X-RateLimit-Reset"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("高并发：200 个线程同时抢 100 个额度，恰好 100 放行 / 100 拒绝（CAS 不超发不漏算）")
    void concurrentConsumptionIsExactlyAccurate() throws InterruptedException {
        String tenantId = "tenant-load";
        int quota = 100;
        int threads = 200;
        dataStore.setQuota(tenantId, quota);
        rateLimitService.reset();

        log.info("[test] tenant={} concurrent quota={} threads={} expecting exact allow=100 deny=100",
                tenantId, quota, threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger denied = new AtomicInteger();

        // 池大小必须 >= 任务数：每个任务都会阻塞在 start 栅栏上，
        // 若工作线程少于任务数，未调度的任务无法对 ready 倒数，形成线程池饥饿死锁。
        pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    var decision = rateLimitService.checkAndConsume(tenantId);
                    if (decision.allowed()) {
                        allowed.incrementAndGet();
                    } else {
                        denied.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未在超时内就绪");
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发判定未在超时内完成");

        log.info("[test] tenant={} concurrent result allowed={} denied={} (判定日志含 count x->y 过程)",
                tenantId, allowed.get(), denied.get());
        assertEquals(quota, allowed.get(),
                "高并发下放行数必须恰好等于配额：多算=超发，少算=漏扣");
        assertEquals(threads - quota, denied.get(), "其余请求必须全部被拒绝");
    }

    @Test
    @DisplayName("小配额多轮并发：每轮 30 线程抢 5 个额度，重复 40 轮全部精确")
    void smallQuotaConcurrentRepeatedly() throws InterruptedException {
        int rounds = 40;
        for (int round = 0; round < rounds; round++) {
            rateLimitService.reset();
            assertExactConcurrentResult("tenant-a", 5, 30);
        }
        log.info("[test] tenant=tenant-a concurrent rounds={} quota=5 threads=30 every round exact", rounds);
    }

    @Test
    @DisplayName("窗口刷新：耗尽配额后推进到下一窗口，计数归零，重新放行且窗口边界可复现")
    void quotaRefreshesAtWindowBoundary() throws Exception {
        // 当前窗口内耗尽 5 个配额。
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/orders").header("X-Api-Key", "secret-tenant-a-viewer"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/orders").header("X-Api-Key", "secret-tenant-a-viewer"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-RateLimit-Remaining", "0"));

        // 边界必须基于测试时钟本身计算，避免与真实时间的毫秒差导致抖动。
        long nowMillis = clock.instant().toEpochMilli();
        long windowStart = nowMillis - (nowMillis % RateLimitService.WINDOW_MILLIS);
        long expectedReset = windowStart + RateLimitService.WINDOW_MILLIS;
        log.info("[test] tenant=tenant-a windowStart={} expecting resetAt={}", windowStart, expectedReset);

        // 拒绝响应声明的刷新时刻必须等于由固定窗口公式推出的边界（语义可复现）。
        var deniedResult = mockMvc.perform(
                        get("/api/orders").header("X-Api-Key", "secret-tenant-a-viewer"))
                .andExpect(status().isTooManyRequests())
                .andReturn();
        long resetHeader = deniedResult.getResponse().getDateHeader("X-RateLimit-Reset");
        assertEquals(expectedReset, resetHeader,
                "X-RateLimit-Reset 必须等于固定窗口边界 floor(now/60s)*60s + 60s");

        // 推进时钟跨过窗口边界 1 毫秒。
        clock.advanceMillis(RateLimitService.WINDOW_MILLIS + 1);
        log.info("[test] tenant=tenant-a basis=clock crossed window boundary expecting count reset to 0");

        mockMvc.perform(get("/api/orders").header("X-Api-Key", "secret-tenant-a-viewer"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "5"))
                .andExpect(header().string("X-RateLimit-Remaining", "4"));
    }

    @Test
    @DisplayName("租户独立：tenant-a 耗尽不影响 tenant-b")
    void quotasAreIndependentPerTenant() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/orders").header("X-Api-Key", "secret-tenant-a-viewer"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/orders").header("X-Api-Key", "secret-tenant-a-viewer"))
                .andExpect(status().isTooManyRequests());

        log.info("[test] tenant=tenant-b basis=own window expecting remaining=2 after one call (quota=3)");
        mockMvc.perform(get("/api/orders").header("X-Api-Key", "secret-tenant-b-viewer"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "3"))
                .andExpect(header().string("X-RateLimit-Remaining", "2"));
    }

    /**
     * 启动 {@code threadCount} 个线程同时对同一租户做一次配额判定，
     * 断言放行数恰好等于配额（且其余全部拒绝）。
     */
    private void assertExactConcurrentResult(String tenantId, int quota, int threadCount)
            throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger denied = new AtomicInteger();

        ExecutorService localPool = Executors.newFixedThreadPool(threadCount);
        try {
            for (int i = 0; i < threadCount; i++) {
                localPool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        var decision = rateLimitService.checkAndConsume(tenantId);
                        if (decision.allowed()) {
                            allowed.incrementAndGet();
                        } else {
                            denied.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未在超时内就绪");
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "并发判定未在超时内完成");
        } finally {
            localPool.shutdownNow();
        }

        assertEquals(quota, allowed.get(),
                "高并发下放行数必须恰好等于配额：多算=超发，少算=漏扣");
        assertEquals(threadCount - quota, denied.get(), "其余请求必须全部被拒绝");
    }
}
