package com.github.h3yow.multitenantapigatewayjava.tests.support;

import com.github.h3yow.multitenantapigatewayjava.gateway.model.ApiKey;
import com.github.h3yow.multitenantapigatewayjava.gateway.model.Role;
import com.github.h3yow.multitenantapigatewayjava.gateway.model.Tenant;
import com.github.h3yow.multitenantapigatewayjava.gateway.resource.ResourceRecord;
import com.github.h3yow.multitenantapigatewayjava.gateway.resource.ResourceRepository;
import com.github.h3yow.multitenantapigatewayjava.gateway.security.InMemoryTenantDirectory;

/**
 * 测试辅助：注册带独立配额的临时租户与本租户资源，保证并发/窗口限流测试互不干扰、可重复。
 */
public class TestTenants {

    private final InMemoryTenantDirectory directory;
    private final ResourceRepository resources;
    private final MutableGatewayClock clock;

    public TestTenants(InMemoryTenantDirectory directory, ResourceRepository resources,
                       MutableGatewayClock clock) {
        this.directory = directory;
        this.resources = resources;
        this.clock = clock;
    }

    /**
     * 注册租户与只读密钥，同时种一条属于该租户的资源，返回该资源 id（GET 它必然 200）。
     */
    public String registerTenantWithKeyAndResource(String tenantId, String apiKey, long quota, String resourceId) {
        register(tenantId, apiKey, quota, Role.READER);
        resources.save(new ResourceRecord(resourceId, tenantId, "test-resource", "owned"));
        return resourceId;
    }

    /** 注册指定角色与高配额的租户（写操作并发测试用），可不种资源。 */
    public void register(String tenantId, String apiKey, long quota, Role role) {
        directory.registerTenant(new Tenant(tenantId, quota));
        directory.registerApiKey(new ApiKey(apiKey, tenantId, role,
                clock.now().plusSeconds(365L * 24 * 3600L), false));
    }
}
