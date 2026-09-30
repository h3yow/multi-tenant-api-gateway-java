package com.github.h3yow.multitenantapigatewayjava.tests.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;

/** 用可控时钟替换生产环境的系统时钟，便于确定性地测试窗口刷新与过期。 */
@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    public Clock mutableClock() {
        return new MutableClock();
    }
}
