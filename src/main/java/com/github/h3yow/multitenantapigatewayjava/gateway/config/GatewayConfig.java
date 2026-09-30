package com.github.h3yow.multitenantapigatewayjava.gateway.config;

import com.github.h3yow.multitenantapigatewayjava.gateway.idempotency.HandlerExecutionMarkingInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfig implements WebMvcConfigurer {

    @Bean
    public GatewayClock gatewayClock() {
        return new SystemGatewayClock();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerExecutionMarkingInterceptor())
                .addPathPatterns("/api/**");
    }
}
