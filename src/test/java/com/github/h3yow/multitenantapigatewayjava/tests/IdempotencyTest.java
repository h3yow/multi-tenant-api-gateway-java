package com.github.h3yow.multitenantapigatewayjava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 幂等测试：
 * 1) 同 key + 同体重放：只真正生效一次（订单只增一条），重复请求拿到与首次一致的状态码与响应体；
 * 2) 同 key + 不同体：409 冲突拒绝，且不覆盖旧结果（再次同体重放仍返回首次结果）；
 * 3) 同 key 并发：所有请求拿到同一首次结果，只创建一条订单；
 * 4) 首次失败也被缓存，重复请求拿到同样的失败结果（不会再次真正执行业务）；
 * 5) 幂等键按租户隔离。
 */
class IdempotencyTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyTest.class);

    private static final String ADMIN = "secret-tenant-a-admin";
    private static final String VIEWER = "secret-tenant-a-viewer";
    private static final String BODY_A = "{\"name\":\"alpha\",\"content\":\"c1\"}";
    private static final String BODY_B = "{\"name\":\"beta\",\"content\":\"c2\"}";

    @Test
    @DisplayName("同 key 同体重放：真正生效一次，重复请求返回一致结果，订单只增加一条")
    void sameKeySameBodyReplaysFirstResponse() throws Exception {
        log.info("[test] tenant=tenant-a key=k1 basis=FIRST then REPLAY same body expecting 1 order created");

        String first = mockMvc.perform(post("/api/orders")
                        .header("X-Api-Key", ADMIN)
                        .header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_A))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("o-1"))
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/orders")
                        .header("X-Api-Key", ADMIN)
                        .header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_A))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("o-1"))
                .andReturn().getResponse().getContentAsString();

        String third = mockMvc.perform(post("/api/orders")
                        .header("X-Api-Key", ADMIN)
                        .header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_A))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertEquals(first, second, "重复请求必须拿到与首次一致的响应体");
        assertEquals(first, third, "多次重复也必须一致");
        // 种子 2 条 + 真正生效的创建 1 条。
        assertEquals(3, dataStore.orderCount("tenant-a"),
                "同一幂等键重复提交只能真正生效一次");
    }

    @Test
    @DisplayName("同 key 不同体：409 拒绝，不覆盖旧结果，之后同体仍重放首次结果")
    void sameKeyDifferentBodyConflictsAndDoesNotOverwrite() throws Exception {
        log.info("[test] tenant=tenant-a key=k2 basis=hash differs expecting CONFLICT and no overwrite");

        mockMvc.perform(post("/api/orders")
                        .header("X-Api-Key", ADMIN)
                        .header("Idempotency-Key", "k2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_A))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("o-1"));

        mockMvc.perform(post("/api/orders")
                        .header("X-Api-Key", ADMIN)
                        .header("Idempotency-Key", "k2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_B))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"));

        // 冲突没有覆盖旧结果：再用首次的 body 重放，仍是首次创建的 o-1。
        mockMvc.perform(post("/api/orders")
                        .header("X-Api-Key", ADMIN)
                        .header("Idempotency-Key", "k2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_A))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("o-1"));

        assertEquals(3, dataStore.orderCount("tenant-a"), "冲突请求不得产生新订单");
    }

    @Test
    @DisplayName("同 key 并发重复提交：所有响应一致且只真正创建一条订单")
    void concurrentSameKeyExecutesExactlyOnce() throws Exception {
        int threads = 16;
        log.info("[test] tenant=tenant-a key=k3 concurrent threads={} expecting one execution, all replay",
                threads);
        // 并发请求都必须先通过限流，这里把该用例的租户配额调大，专注验证幂等语义。
        dataStore.setQuota("tenant-a", 100);

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger status201 = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        var result = mockMvc.perform(post("/api/orders")
                                        .header("X-Api-Key", ADMIN)
                                        .header("Idempotency-Key", "k3")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(BODY_A))
                                .andReturn();
                        if (result.getResponse().getStatus() == 201) {
                            status201.incrementAndGet();
                        } else {
                            other.incrementAndGet();
                        }
                    } catch (Exception e) {
                        other.incrementAndGet();
                    }
                });
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        log.info("[test] tenant=tenant-a key=k3 concurrent result 201={} other={} (all must replay first 201)",
                status201.get(), other.get());
        assertEquals(threads, status201.get(), "并发重复请求都应拿到首次的 201 响应（等待首次完成后重放）");
        assertEquals(0, other.get());
        assertEquals(3, dataStore.orderCount("tenant-a"), "并发下同一幂等键也只能真正创建一条订单");
    }

    @Test
    @DisplayName("首次失败也被缓存：VIEWER 同 key 重复提交始终拿到相同的 403，不会多次进入业务判定")
    void failedFirstRequestIsCachedAndReplayed() throws Exception {
        log.info("[test] tenant=tenant-a key=k4 basis=first failure 403 cached and replayed");

        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(post("/api/orders")
                            .header("X-Api-Key", VIEWER)
                            .header("Idempotency-Key", "k4")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY_A))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("FORBIDDEN"));
        }
        // 审计中应有一条首次之后两条幂等重放记录，证明失败结果同样被重放而非重复执行。
        long replays = auditLog.eventsForTenant("tenant-a").stream()
                .filter(e -> e.action()
                        == com.github.h3yow.multitenantapigatewayjava.audit.AuditAction.IDEMPOTENCY_REPLAY)
                .count();
        assertEquals(2, replays, "第 2、3 次必须走幂等重放");
    }

    @Test
    @DisplayName("幂等键按租户隔离：不同租户使用相同 key 各自生效")
    void idempotencyKeyIsScopedPerTenant() throws Exception {
        log.info("[test] tenant=tenant-a/b key=shared basis=composite key includes tenant");

        mockMvc.perform(post("/api/orders")
                        .header("X-Api-Key", "secret-tenant-a-admin")
                        .header("Idempotency-Key", "shared")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_A))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("o-1"));

        mockMvc.perform(post("/api/orders")
                        .header("X-Api-Key", "secret-tenant-b-admin")
                        .header("Idempotency-Key", "shared")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_A))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("o-1"));

        // tenant-b 有 1 条种子 + 本次各自独立生效的 1 条新建。
        assertEquals(2, dataStore.orderCount("tenant-b"));
    }
}
