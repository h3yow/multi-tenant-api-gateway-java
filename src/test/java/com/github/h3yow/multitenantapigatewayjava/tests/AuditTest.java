package com.github.h3yow.multitenantapigatewayjava.tests;

import com.github.h3yow.multitenantapigatewayjava.gateway.audit.AuditEvent;
import com.github.h3yow.multitenantapigatewayjava.gateway.audit.AuditService;
import com.github.h3yow.multitenantapigatewayjava.tests.support.AbstractGatewayTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审计：成功与每条拒绝路径都必须留下可追溯记录，reason 与判定一致。
 */
class AuditTest extends AbstractGatewayTest {

    private static final Logger log = LoggerFactory.getLogger("TEST-AUDIT");

    @Autowired
    private AuditService auditService;

    @Test
    void everyDecisionIncludingRejectionsIsAudited() throws Exception {
        // 1) 认证拒绝
        mockMvc.perform(get("/api/resources/res-acme-1").header("X-Api-Key", "nope"))
                .andExpect(status().isUnauthorized());
        // 2) 成功访问
        mockMvc.perform(get("/api/resources/res-acme-1").header("X-Api-Key", "acme-reader-key-2026"))
                .andExpect(status().isOk());
        // 3) 鉴权拒绝
        mockMvc.perform(post("/api/resources")
                        .header("X-Api-Key", "acme-reader-key-2026")
                        .header("Content-Type", "application/json").content("{}"))
                .andExpect(status().isForbidden());
        // 4) 幂等冲突拒绝
        mockMvc.perform(post("/api/resources")
                        .header("X-Api-Key", "globex-writer-key-2026")
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", "audit-key").content("{\"name\":\"a\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/resources")
                        .header("X-Api-Key", "globex-writer-key-2026")
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", "audit-key").content("{\"name\":\"different\"}"))
                .andExpect(status().isConflict());

        boolean sawUnknown = auditService.all().stream().map(AuditEvent::reason).anyMatch("unknown_api_key"::equals);
        boolean sawForbidden = auditService.all().stream().map(AuditEvent::reason).anyMatch("forbidden_role"::equals);
        boolean sawConflict = auditService.all().stream().map(AuditEvent::reason)
                .anyMatch("idempotency_key_conflict"::equals);
        boolean sawAllow = auditService.all().stream().anyMatch(e -> "acme".equals(e.tenantId())
                && "ALLOW".equals(e.decision()) && e.httpStatus() == 200);

        auditService.findByTenant("acme").forEach(e -> log.info(
                "[审计] tenant={} role={} {} {} status={} decision={} reason={} key={} replay={}",
                e.tenantId(), e.role(), e.method(), e.path(), e.httpStatus(),
                e.decision(), e.reason(), e.idempotencyKey(), e.replay()));

        assertTrue(sawUnknown, "认证拒绝路径必须被审计");
        assertTrue(sawForbidden, "鉴权拒绝路径必须被审计");
        assertTrue(sawConflict, "幂等冲突拒绝路径必须被审计");
        assertTrue(sawAllow, "成功路径必须被审计");
    }
}
