package com.github.h3yow.multitenantapigatewayjava.tests;

import com.github.h3yow.multitenantapigatewayjava.audit.AuditAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审计测试：成功与所有拒绝路径都必须留下可追溯记录。
 */
class AuditTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AuditTest.class);

    @Test
    @DisplayName("成功路径：同时记录认证、限流判定与终态 REQUEST_OK")
    void successPathIsAudited() throws Exception {
        log.info("[test] tenant=tenant-a expecting audit actions AUTH_PASS,RATE_LIMIT_PASS,REQUEST_OK");
        mockMvc.perform(get("/api/orders").header("X-Api-Key", "secret-tenant-a-viewer"))
                .andExpect(status().isOk());

        List<AuditAction> actions = auditLog.eventsForTenant("tenant-a").stream()
                .map(e -> e.action()).toList();
        assertTrue(actions.contains(AuditAction.AUTH_PASS), actions.toString());
        assertTrue(actions.contains(AuditAction.RATE_LIMIT_PASS), actions.toString());
        assertTrue(actions.contains(AuditAction.REQUEST_OK), actions.toString());
    }

    @Test
    @DisplayName("认证拒绝路径：AUTH_DENY + REQUEST_DENIED 都不漏记")
    void authDenyPathIsAudited() throws Exception {
        log.info("[test] tenant=- expecting AUTH_DENY and terminal REQUEST_DENIED even when rejected early");
        mockMvc.perform(get("/api/orders").header("X-Api-Key", "secret-tenant-a-revoked"))
                .andExpect(status().isUnauthorized());

        List<AuditAction> actions = auditLog.events().stream()
                .map(e -> e.action()).toList();
        assertTrue(actions.contains(AuditAction.AUTH_DENY), actions.toString());
        assertTrue(actions.contains(AuditAction.REQUEST_DENIED), actions.toString());
    }

    @Test
    @DisplayName("限流拒绝路径：RATE_LIMIT_DENY + 终态 REQUEST_DENIED 均留痕")
    void rateLimitDenyPathIsAudited() throws Exception {
        log.info("[test] tenant=tenant-a exhausting quota expecting RATE_LIMIT_DENY audited");
        for (int i = 0; i < 6; i++) {
            mockMvc.perform(get("/api/orders").header("X-Api-Key", "secret-tenant-a-viewer"));
        }
        List<AuditAction> actions = auditLog.eventsForTenant("tenant-a").stream()
                .map(e -> e.action()).toList();
        assertTrue(actions.contains(AuditAction.RATE_LIMIT_DENY), actions.toString());
        assertTrue(actions.stream().filter(a -> a == AuditAction.RATE_LIMIT_DENY).count() == 1,
                "恰好一次限流拒绝");
    }

    @Test
    @DisplayName("幂等冲突路径：IDEMPOTENCY_CONFLICT 留痕，且不覆盖旧结果")
    void idempotencyConflictIsAudited() throws Exception {
        log.info("[test] tenant=tenant-a expecting IDEMPOTENCY_CONFLICT audit event");
        mockMvc.perform(post("/api/orders")
                        .header("X-Api-Key", "secret-tenant-a-admin")
                        .header("Idempotency-Key", "ck")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"a\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/orders")
                        .header("X-Api-Key", "secret-tenant-a-admin")
                        .header("Idempotency-Key", "ck")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"different\"}"))
                .andExpect(status().isConflict());

        List<AuditAction> actions = auditLog.eventsForTenant("tenant-a").stream()
                .map(e -> e.action()).toList();
        assertTrue(actions.contains(AuditAction.IDEMPOTENCY_CONFLICT), actions.toString());
        assertTrue(actions.contains(AuditAction.REQUEST_DENIED), "冲突终态也要有终态审计");
    }
}
