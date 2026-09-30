package com.github.h3yow.multitenantapigatewayjava.gateway.security;

import com.github.h3yow.multitenantapigatewayjava.gateway.model.ApiKey;
import com.github.h3yow.multitenantapigatewayjava.gateway.model.Tenant;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版租户目录。种子数据与测试动态租户都通过 registerXxx 写入，读写均线程安全。
 */
public class InMemoryTenantDirectory implements TenantDirectory {

    private final ConcurrentHashMap<String, ApiKey> apiKeys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Tenant> tenants = new ConcurrentHashMap<>();

    @Override
    public ApiKey findApiKey(String key) {
        return apiKeys.get(key);
    }

    @Override
    public Tenant findTenant(String tenantId) {
        return tenants.get(tenantId);
    }

    public void registerTenant(Tenant tenant) {
        tenants.put(tenant.id(), tenant);
    }

    public void registerApiKey(ApiKey apiKey) {
        apiKeys.put(apiKey.key(), apiKey);
    }

    /**
     * 测试辅助：模拟密钥被主动吊销。
     */
    public void revoke(String key) {
        ApiKey current = apiKeys.get(key);
        if (current != null) {
            apiKeys.put(key, new ApiKey(current.key(), current.tenantId(), current.role(),
                    current.expiresAt(), true));
        }
    }
}
