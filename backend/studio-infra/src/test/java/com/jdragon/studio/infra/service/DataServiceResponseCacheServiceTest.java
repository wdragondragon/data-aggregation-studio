package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataServiceResponseCacheServiceTest {

    @Test
    void shouldAlwaysBypassLocalFallbackWhenRedisIsUnavailable() {
        DataServiceResponseCacheService service = new DataServiceResponseCacheService(
                unavailableRedis(), new ObjectMapper());

        service.put(1L, "key", Map.of("value", "cached"), 1L, 60_000L);
        assertNull(service.get(1L, "key"));
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<StringRedisTemplate> unavailableRedis() {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
