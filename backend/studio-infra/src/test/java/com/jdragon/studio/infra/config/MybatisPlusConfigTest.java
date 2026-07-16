package com.jdragon.studio.infra.config;

import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MybatisPlusConfigTest {

    @Test
    void shouldRegisterOptimisticLockerInterceptor() {
        assertTrue(new MybatisPlusConfig().mybatisPlusInterceptor().getInterceptors().stream()
                .anyMatch(OptimisticLockerInnerInterceptor.class::isInstance));
    }
}
