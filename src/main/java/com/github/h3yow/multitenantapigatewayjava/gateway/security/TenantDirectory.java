package com.github.h3yow.multitenantapigatewayjava.gateway.security;

import com.github.h3yow.multitenantapigatewayjava.gateway.model.ApiKey;
import com.github.h3yow.multitenantapigatewayjava.gateway.model.Tenant;

public interface TenantDirectory {

    ApiKey findApiKey(String key);

    Tenant findTenant(String tenantId);
}
