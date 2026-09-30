package com.github.h3yow.multitenantapigatewayjava.gateway.config;

import com.github.h3yow.multitenantapigatewayjava.gateway.model.ApiKey;
import com.github.h3yow.multitenantapigatewayjava.gateway.model.Role;
import com.github.h3yow.multitenantapigatewayjava.gateway.model.Tenant;
import com.github.h3yow.multitenantapigatewayjava.gateway.security.InMemoryTenantDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;

/**
 * 启动时注册演示租户与密钥。密钥命名即文档，便于手工 curl 验证：
 * acme-{admin,writer,reader}-key-2026 等。另含一把过期密钥与一把吊销密钥。
 */
@Configuration
public class TenantBootstrap {

    private static final Logger log = LoggerFactory.getLogger(TenantBootstrap.class);

    @Bean
    public InMemoryTenantDirectory tenantDirectory() {
        return new InMemoryTenantDirectory();
    }

    @Bean
    public CommandLineRunner seedTenants(InMemoryTenantDirectory directory, GatewayClock clock) {
        return args -> {
            directory.registerTenant(new Tenant("acme", 5L));
            directory.registerTenant(new Tenant("globex", 3L));

            Instant farFuture = clock.now().plusSeconds(365 * 24 * 3600L);
            Instant past = clock.now().minusSeconds(24 * 3600L);

            register(directory, new ApiKey("acme-admin-key-2026", "acme", Role.ADMIN, farFuture, false));
            register(directory, new ApiKey("acme-writer-key-2026", "acme", Role.WRITER, farFuture, false));
            register(directory, new ApiKey("acme-reader-key-2026", "acme", Role.READER, farFuture, false));

            register(directory, new ApiKey("globex-admin-key-2026", "globex", Role.ADMIN, farFuture, false));
            register(directory, new ApiKey("globex-writer-key-2026", "globex", Role.WRITER, farFuture, false));
            register(directory, new ApiKey("globex-reader-key-2026", "globex", Role.READER, farFuture, false));

            // 认证失败的两类特例：已过期、已主动吊销。
            register(directory, new ApiKey("acme-expired-key-2025", "acme", Role.WRITER, past, false));
            register(directory, new ApiKey("acme-revoked-key-2026", "acme", Role.WRITER, farFuture, true));

            log.info("seeded tenants acme(quota=5) globex(quota=3) with per-role api keys");
        };
    }

    private static void register(InMemoryTenantDirectory directory, ApiKey key) {
        directory.registerApiKey(key);
    }
}
