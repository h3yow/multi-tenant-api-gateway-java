package com.github.h3yow.multitenantapigatewayjava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证测试：密钥缺失/无效/过期/吊销必须给出互不相同的拒绝原因；
 * 有效 key 正常通过。判定依据可在 INFO 日志中看到（tenant/reason/basis）。
 */
class AuthenticationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationTest.class);

    @Test
    @DisplayName("缺失密钥：401 + MISSING_KEY")
    void missingKeyIsRejectedWithDistinctReason() throws Exception {
        log.info("[test] tenant=- basis=header absent expecting reason=MISSING_KEY count=n/a");
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("MISSING_KEY"));
    }

    @Test
    @DisplayName("无效密钥：401 + INVALID_KEY")
    void invalidKeyIsRejectedWithDistinctReason() throws Exception {
        log.info("[test] tenant=- basis=unknown secret expecting reason=INVALID_KEY count=n/a");
        mockMvc.perform(get("/api/orders").header("X-Api-Key", "does-not-exist"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_KEY"));
    }

    @Test
    @DisplayName("过期密钥：401 + EXPIRED_KEY")
    void expiredKeyIsRejectedWithDistinctReason() throws Exception {
        log.info("[test] tenant=tenant-a basis=now>=expiresAt expecting reason=EXPIRED_KEY count=n/a");
        mockMvc.perform(get("/api/orders").header("X-Api-Key", "secret-tenant-a-expired"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("EXPIRED_KEY"));
    }

    @Test
    @DisplayName("被吊销密钥：401 + REVOKED_KEY（区别于过期）")
    void revokedKeyIsRejectedWithDistinctReason() throws Exception {
        log.info("[test] tenant=tenant-a basis=revoked flag expecting reason=REVOKED_KEY count=n/a");
        mockMvc.perform(get("/api/orders").header("X-Api-Key", "secret-tenant-a-revoked"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("REVOKED_KEY"));
    }

    @Test
    @DisplayName("有效密钥：认证通过并返回数据")
    void validKeyPasses() throws Exception {
        log.info("[test] tenant=tenant-a basis=valid active key expecting PASS count=n/a");
        mockMvc.perform(get("/api/orders").header("X-Api-Key", "secret-tenant-a-viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("o-seed-1"));
    }
}
