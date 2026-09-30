package com.github.h3yow.multitenantapigatewayjava.gateway.resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 预置资源：每个租户至少一条，可立即用于跨租户不可见性验证。
 */
@Configuration
public class ResourceBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ResourceBootstrap.class);

    @Bean
    public CommandLineRunner seedResources(ResourceRepository repository) {
        return args -> {
            repository.save(new ResourceRecord("res-acme-1", "acme", "acme-secret", "acme internal brief"));
            repository.save(new ResourceRecord("res-acme-2", "acme", "acme-roadmap", "acme roadmap note"));
            repository.save(new ResourceRecord("res-globex-1", "globex", "globex-secret", "globex internal brief"));
            log.info("seeded demo resources for tenants acme and globex");
        };
    }
}
