package com.github.h3yow.multitenantapigatewayjava.tests;

import com.github.h3yow.multitenantapigatewayjava.tests.support.AbstractGatewayTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 授权：角色边界 + 跨租户不可见性。
 * 关键断言：访问他租户资源与访问不存在资源得到字节级一致的 404，无法据此判断资源是否存在。
 */
class AuthorizationTest extends AbstractGatewayTest {

    private static final Logger log = LoggerFactory.getLogger("TEST-AUTHZ");

    @Test
    void crossTenantReadIsIndistinguishableFromNotFound() throws Exception {
        log.info("[判定依据] 场景=越权读他租户资源 租户=acme 目标=res-globex-1 期望=404 与不存在一致");
        String crossTenantBody = mockMvc.perform(get("/api/resources/res-globex-1")
                        .header("X-Api-Key", "acme-reader-key-2026"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        log.info("[判定依据] 场景=读不存在资源 租户=acme 目标=missing 期望=相同404");
        String notFoundBody = mockMvc.perform(get("/api/resources/no-such-resource")
                        .header("X-Api-Key", "acme-reader-key-2026"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        // 两者响应体必须完全一致，杜绝存在性泄漏。
        org.junit.jupiter.api.Assertions.assertEquals(notFoundBody, crossTenantBody,
                "跨租户访问与不存在资源的响应体必须一致，不得泄漏资源存在性");
        log.info("[判定依据] 两种404响应体一致: {}", crossTenantBody);
    }

    @Test
    void readerCannotCreateResources() throws Exception {
        log.info("[判定依据] 场景=READER创建 租户=acme 期望=403 forbidden_role");
        mockMvc.perform(post("/api/resources")
                        .header("X-Api-Key", "acme-reader-key-2026")
                        .header("Content-Type", "application/json")
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden_role"));
    }

    @Test
    void writerCanCreateButNotDelete() throws Exception {
        log.info("[判定依据] 场景=WRITER创建 租户=acme 期望=201");
        mockMvc.perform(post("/api/resources")
                        .header("X-Api-Key", "acme-writer-key-2026")
                        .header("Content-Type", "application/json")
                        .content("{\"name\":\"created-by-writer\",\"content\":\"ok\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerTenant").value("acme"));

        log.info("[判定依据] 场景=WRITER删除 租户=acme 期望=403 forbidden_role");
        mockMvc.perform(delete("/api/resources/res-acme-1")
                        .header("X-Api-Key", "acme-writer-key-2026"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden_role"));
    }

    @Test
    void adminCanDeleteWithinOwnTenant() throws Exception {
        log.info("[判定依据] 场景=ADMIN删除本租户资源 租户=globex 期望=200");
        mockMvc.perform(delete("/api/resources/res-globex-1")
                        .header("X-Api-Key", "globex-admin-key-2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
    }

    @Test
    void adminDeletingOtherTenantResourceStillSees404() throws Exception {
        // globex 管理员也不能借 ADMIN 角色看到 acme 资源存在与否。
        log.info("[判定依据] 场景=ADMIN越权删他租户 租户=globex 目标=acme 期望=404");
        mockMvc.perform(delete("/api/resources/res-acme-1")
                        .header("X-Api-Key", "globex-admin-key-2026"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"error\":{\"code\":\"error_404\",\"message\":\"resource not found\"}}"));
    }
}
