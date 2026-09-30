package com.github.h3yow.multitenantapigatewayjava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 角色鉴权测试：同租户内 ADMIN/VIEWER 的范围分开；
 * 越权访问受限资源与访问根本不存在的资源必须返回字节级一致的响应，
 * 从而无法通过响应差异判断资源是否存在。
 */
class AuthorizationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationTest.class);

    private static final String ADMIN = "secret-tenant-a-admin";
    private static final String VIEWER = "secret-tenant-a-viewer";

    @Test
    @DisplayName("VIEWER 列表只看到普通资源，受限资源不出现在列表中")
    void viewerListHidesScopedResource() throws Exception {
        log.info("[test] tenant=tenant-a key=viewer basis=scoped hidden from list expecting count=1");
        String body = mockMvc.perform(get("/api/orders").header("X-Api-Key", VIEWER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("o-seed-1"), "普通资源应当可见");
        assertFalse(body.contains("o-seed-2"), "受限资源不应出现在列表中");
        assertFalse(body.contains("order-secret"), "受限资源名称也不能泄漏");
    }

    @Test
    @DisplayName("ADMIN 列表能看到受限资源")
    void adminListSeesScopedResource() throws Exception {
        log.info("[test] tenant=tenant-a key=admin basis=role==ADMIN expecting scoped visible count=2");
        mockMvc.perform(get("/api/orders").header("X-Api-Key", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='o-seed-2')]").exists());
    }

    @Test
    @DisplayName("VIEWER 读取受限资源：404，且响应与读取不存在资源完全一致")
    void viewerAccessingScopedResourceLooksExactlyLikeMissing() throws Exception {
        log.info("[test] tenant=tenant-a key=viewer basis=scoped vs absent must be indistinguishable");

        MvcResult deniedScoped = mockMvc
                .perform(get("/api/orders/o-seed-2").header("X-Api-Key", VIEWER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andReturn();

        MvcResult absent = mockMvc
                .perform(get("/api/orders/does-not-exist").header("X-Api-Key", VIEWER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andReturn();

        // path 仅回显调用方自己请求的 URL（调用方本就知道），不构成存在性 oracle。
        // 承载服务端判定结果的 status/error/message 三个字段必须逐字一致。
        assertEquals(absent.getResponse().getStatus(), deniedScoped.getResponse().getStatus());
        assertEquals(absent.getResponse().getContentAsString().replaceAll(".*\"error\":\"([^\"]+)\".*", "$1"),
                deniedScoped.getResponse().getContentAsString().replaceAll(".*\"error\":\"([^\"]+)\".*", "$1"));
        String deniedStable = deniedScoped.getResponse().getContentAsString()
                .replaceAll("\"timestamp\":\\d+", "\"timestamp\":0")
                .replaceAll("\"path\":\"[^\"]*\"", "\"path\":\"/x\"");
        String absentStable = absent.getResponse().getContentAsString()
                .replaceAll("\"timestamp\":\\d+", "\"timestamp\":0")
                .replaceAll("\"path\":\"[^\"]*\"", "\"path\":\"/x\"");
        assertEquals(absentStable, deniedStable,
                "越权响应与不存在响应在忽略时间戳与请求路径后必须完全相同，避免泄漏资源存在性");
        assertFalse(deniedScoped.getResponse().getContentAsString().contains("order-secret"),
                "响应体中不得泄漏受限资源内容");
    }

    @Test
    @DisplayName("ADMIN 可读受限资源")
    void adminCanReadScopedResource() throws Exception {
        log.info("[test] tenant=tenant-a key=admin basis=role==ADMIN resource=o-seed-2 expecting 200");
        mockMvc.perform(get("/api/orders/o-seed-2").header("X-Api-Key", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("o-seed-2"));
    }

    @Test
    @DisplayName("VIEWER 写请求被端点级拒绝（403）")
    void viewerCannotCreate() throws Exception {
        log.info("[test] tenant=tenant-a key=viewer basis=role!=ADMIN action=CREATE expecting 403");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/orders")
                        .header("X-Api-Key", VIEWER)
                        .contentType("application/json")
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("租户隔离：tenant-a 的 key 访问不到 tenant-b 的资源")
    void tenantIsolationHidesOtherTenantResource() throws Exception {
        log.info("[test] tenant=tenant-a key=admin basis=lookup scoped to own tenant expecting 404 for o-seed-3");
        mockMvc.perform(get("/api/orders/o-seed-3").header("X-Api-Key", ADMIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }
}
