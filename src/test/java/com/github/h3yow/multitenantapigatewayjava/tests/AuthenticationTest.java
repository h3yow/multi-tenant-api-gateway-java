package com.github.h3yow.multitenantapigatewayjava.tests;

import com.github.h3yow.multitenantapigatewayjava.tests.support.AbstractGatewayTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证：缺失 / 无效 / 过期 / 吊销 必须给出互不相同的拒绝原因。
 */
class AuthenticationTest extends AbstractGatewayTest {

    private static final Logger log = LoggerFactory.getLogger("TEST-AUTH");

    @Test
    void missingKeyIsRejectedWithDistinctReason() throws Exception {
        log.info("[判定依据] 场景=缺失密钥 期望=401 missing_api_key");
        mockMvc.perform(get("/api/resources/res-acme-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("missing_api_key"));
    }

    @Test
    void unknownKeyIsRejectedWithDistinctReason() throws Exception {
        log.info("[判定依据] 场景=密钥不存在 租户=- 期望=401 unknown_api_key");
        mockMvc.perform(get("/api/resources/res-acme-1").header("X-Api-Key", "does-not-exist"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("unknown_api_key"));
    }

    @Test
    void expiredKeyIsRejectedWithDistinctReason() throws Exception {
        log.info("[判定依据] 场景=密钥已过期 租户=acme 期望=401 api_key_expired");
        mockMvc.perform(get("/api/resources/res-acme-1").header("X-Api-Key", "acme-expired-key-2025"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("api_key_expired"));
    }

    @Test
    void revokedKeyIsRejectedWithDistinctReason() throws Exception {
        log.info("[判定依据] 场景=密钥已吊销 租户=acme 期望=401 api_key_revoked");
        mockMvc.perform(get("/api/resources/res-acme-1").header("X-Api-Key", "acme-revoked-key-2026"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("api_key_revoked"));
    }

    @Test
    void validKeyPassesAuthentication() throws Exception {
        log.info("[判定依据] 场景=有效密钥 租户=acme 角色=READER 期望=200");
        mockMvc.perform(get("/api/resources/res-acme-1").header("X-Api-Key", "acme-reader-key-2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerTenant").value("acme"));
    }
}
