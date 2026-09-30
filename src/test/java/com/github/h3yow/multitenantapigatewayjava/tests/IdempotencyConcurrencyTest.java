package com.github.h3yow.multitenantapigatewayjava.tests;

import com.github.h3yow.multitenantapigatewayjava.gateway.model.Role;
import com.github.h3yow.multitenantapigatewayjava.tests.support.AbstractGatewayTest;
import com.github.h3yow.multitenantapigatewayjava.tests.support.TestTenants;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 并发幂等：同一幂等键 + 相同请求体并发提交时，只能真正生效一次；
 * 其余请求要么拿到首次结果（201 重放），要么在首次仍执行中时拿到 409 in_use。
 * 但最终（首次完成后）所有同体重放都必须得到同一个资源 id。
 */
class IdempotencyConcurrencyTest extends AbstractGatewayTest {

    private static final Logger log = LoggerFactory.getLogger("TEST-IDEMPOTENCY-CONC");

    @Autowired
    private TestTenants testTenants;

    @Test
    void sameKeyConcurrentlyResultsInSingleEffectiveCreation() throws Exception {
        testTenants.register("tenant-idem-conc", "idem-conc-key", 10_000L, Role.WRITER);
        String key = "idem-concurrent-1";
        String body = "{\"name\":\"race\",\"content\":\"same\"}";
        int threads = 24;

        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger replay = new AtomicInteger();
        AtomicInteger inFlight = new AtomicInteger();
        Set<String> createdResourceIds = ConcurrentHashMap.newKeySet();

        log.info("[判定依据] 并发幂等开始 键={} 线程={} 期望真正创建=1", key, threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    var resp = mockMvc.perform(post("/api/resources")
                                    .header("X-Api-Key", "idem-conc-key")
                                    .header("Content-Type", "application/json")
                                    .header("Idempotency-Key", key)
                                    .content(body))
                            .andReturn().getResponse();
                    int status = resp.getStatus();
                    if (status == 201 && "true".equals(resp.getHeader("Idempotent-Replay"))) {
                        replay.incrementAndGet();
                    } else if (status == 201) {
                        created.incrementAndGet();
                    } else if (status == 409) {
                        inFlight.incrementAndGet();
                    }
                    if (status == 201) {
                        createdResourceIds.add(extractId(resp.getContentAsString()));
                    }
                } catch (Exception e) {
                    log.error("[判定依据] 并发请求异常", e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发请求应在超时前结束");
        pool.shutdown();

        log.info("[判定依据] 并发幂等结束 真正创建={} 重放201={} 执行中409={} 资源id集合={}",
                created.get(), replay.get(), inFlight.get(), createdResourceIds);

        assertEquals(1, created.get(), "同键同体必须只真正创建一次");
        assertEquals(1, createdResourceIds.size(), "所有201结果必须指向同一资源");

        // 等首次完成后，再来一发同体请求，必须稳定重放到那唯一资源。
        String finalReplay = mockMvc.perform(post("/api/resources")
                        .header("X-Api-Key", "idem-conc-key")
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", key)
                        .content(body))
                .andExpect(result -> assertEquals(201, result.getResponse().getStatus()))
                .andReturn().getResponse().getContentAsString();
        log.info("[判定依据] 收敛后重放资源={}", extractId(finalReplay));
        assertTrue(createdResourceIds.contains(extractId(finalReplay)),
                "收敛后的重放必须返回首次创建的资源 id");
        Set<String> one = new HashSet<>(createdResourceIds);
        assertEquals(1, one.size());
    }

    private static String extractId(String json) {
        int i = json.indexOf("\"id\":\"");
        if (i < 0) {
            return json;
        }
        int from = i + 6;
        int to = json.indexOf('"', from);
        return json.substring(from, to);
    }
}
