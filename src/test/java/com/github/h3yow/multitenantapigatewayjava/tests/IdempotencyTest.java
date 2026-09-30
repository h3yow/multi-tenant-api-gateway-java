package com.github.h3yow.multitenantapigatewayjava.tests;

import com.github.h3yow.multitenantapigatewayjava.tests.support.AbstractGatewayTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 幂等：重放返回与首次一致的结果；同键不同体冲突拒绝且不覆盖旧结果。
 */
class IdempotencyTest extends AbstractGatewayTest {

    private static final Logger log = LoggerFactory.getLogger("TEST-IDEMPOTENCY");

    @Test
    void duplicateSubmissionReturnsFirstResultAndCreatesOnce() throws Exception {
        String key = "idem-create-1";
        String body = "{\"name\":\"once\",\"content\":\"first\"}";

        log.info("[判定依据] 幂等键={} 首次提交 租户=globex 期望201并真正创建", key);
        String first = mockMvc.perform(post("/api/resources")
                        .header("X-Api-Key", "globex-writer-key-2026")
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", key)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        log.info("[判定依据] 幂等键={} 第二次同体提交 期望重放：同状态201/同body/Idempotent-Replay=true", key);
        String second = mockMvc.perform(post("/api/resources")
                        .header("X-Api-Key", "globex-writer-key-2026")
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", key)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andReturn().getResponse().getContentAsString();

        assertEquals(first, second, "重复提交必须返回与首次完全一致的结果");
        log.info("[判定依据] 首次与重放响应一致: {}", first);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejectedWithoutOverwriting() throws Exception {
        String key = "idem-conflict-1";
        String bodyA = "{\"name\":\"alpha\",\"content\":\"A\"}";
        String bodyB = "{\"name\":\"alpha\",\"content\":\"DIFFERENT\"}";

        log.info("[判定依据] 幂等键={} 首次体A 期望201", key);
        String firstResult = mockMvc.perform(post("/api/resources")
                        .header("X-Api-Key", "globex-writer-key-2026")
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", key)
                        .content(bodyA))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        log.info("[判定依据] 幂等键={} 同键换体B 期望409 idempotency_key_conflict", key);
        mockMvc.perform(post("/api/resources")
                        .header("X-Api-Key", "globex-writer-key-2026")
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", key)
                        .content(bodyB))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("idempotency_key_conflict"));

        log.info("[判定依据] 幂等键={} 再次用体A 期望仍重放首次结果（旧结果未被覆盖）", key);
        String replayAfterConflict = mockMvc.perform(post("/api/resources")
                        .header("X-Api-Key", "globex-writer-key-2026")
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", key)
                        .content(bodyA))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andReturn().getResponse().getContentAsString();

        assertEquals(firstResult, replayAfterConflict, "冲突不得覆盖或替换首次结果");
    }

    @Test
    void idempotencyKeyIsScopedPerTenant() throws Exception {
        // 相同幂等键名在不同租户下互不影响，各自首次都应真正执行。
        String sharedKey = "shared-key-name";
        log.info("[判定依据] 同键名={} 租户=globex 首次 期望201", sharedKey);
        mockMvc.perform(post("/api/resources")
                        .header("X-Api-Key", "globex-writer-key-2026")
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", sharedKey)
                        .content("{\"name\":\"g\"}"))
                .andExpect(status().isCreated());

        log.info("[判定依据] 同键名={} 租户=acme 首次 期望201（租户隔离，不判冲突）", sharedKey);
        mockMvc.perform(post("/api/resources")
                        .header("X-Api-Key", "acme-writer-key-2026")
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", sharedKey)
                        .content("{\"name\":\"a\"}"))
                .andExpect(status().isCreated());
    }
}
