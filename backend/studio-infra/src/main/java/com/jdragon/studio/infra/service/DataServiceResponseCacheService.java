package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class DataServiceResponseCacheService {

    private static final Logger log = LoggerFactory.getLogger(DataServiceResponseCacheService.class);

    private static final String KEY_PREFIX = "studio:data-service:cache:";
    private static final String SERVICE_KEYS_PREFIX = "studio:data-service:cache-keys:";
    private static final long SERVICE_KEYS_TTL_DAYS = 7L;

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ObjectMapper objectMapper;
    private final Map<String, LocalCacheEntry> localCache = new ConcurrentHashMap<String, LocalCacheEntry>();

    public DataServiceResponseCacheService(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                           ObjectMapper objectMapper) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void logRedisStatus() {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            log.warn("Data service response cache Redis template is not available, using local memory fallback only");
            return;
        }
        RedisConnectionFactory connectionFactory = redisTemplate.getConnectionFactory();
        if (connectionFactory == null) {
            log.warn("Data service response cache Redis connection factory is not available, using local memory fallback only");
            return;
        }
        RedisConnection connection = null;
        try {
            connection = connectionFactory.getConnection();
            String ping = connection.ping();
            log.info("Data service response cache Redis is available, connectionFactory={}, ping={}",
                    connectionFactory.getClass().getName(), ping);
        } catch (Exception ex) {
            log.warn("Data service response cache Redis is not available, connectionFactory={}, using local memory fallback",
                    connectionFactory.getClass().getName(), ex);
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ex) {
                    log.debug("Failed to close Redis connection after cache availability check", ex);
                }
            }
        }
    }

    public CacheLookup get(Long serviceId, String rawKey) {
        if (serviceId == null || rawKey == null) {
            return null;
        }
        String cacheKey = cacheKey(serviceId, rawKey);
        CacheLookup redisLookup = getFromRedis(cacheKey);
        if (redisLookup != null) {
            return redisLookup;
        }
        return getFromLocal(cacheKey);
    }

    public void put(Long serviceId, String rawKey, Map<String, Object> data, long rowCount, long ttlMillis) {
        if (serviceId == null || rawKey == null || data == null || ttlMillis <= 0L) {
            return;
        }
        String cacheKey = cacheKey(serviceId, rawKey);
        CachePayload payload = new CachePayload();
        payload.data = copyData(data);
        payload.rowCount = rowCount;
        payload.expiresAt = System.currentTimeMillis() + ttlMillis;
        if (!putToRedis(serviceId, cacheKey, payload, ttlMillis)) {
            putToLocal(cacheKey, payload);
        }
    }

    public void evictService(Long serviceId) {
        if (serviceId == null) {
            return;
        }
        evictRedisService(serviceId);
        String prefix = KEY_PREFIX + serviceId + ":";
        for (String key : new ArrayList<String>(localCache.keySet())) {
            if (key.startsWith(prefix)) {
                localCache.remove(key);
            }
        }
    }

    private CacheLookup getFromRedis(String cacheKey) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return null;
        }
        try {
            String value = redisTemplate.opsForValue().get(cacheKey);
            if (value == null || value.trim().isEmpty()) {
                log.debug("Data service response Redis cache miss, key={}", cacheKey);
                return null;
            }
            CachePayload payload = objectMapper.readValue(value, CachePayload.class);
            if (payload == null || payload.data == null) {
                return null;
            }
            log.debug("Data service response Redis cache hit, key={}", cacheKey);
            return new CacheLookup(copyData(payload.data), payload.rowCount);
        } catch (Exception ex) {
            log.warn("Failed to read data service response cache from Redis, fallback to local cache", ex);
            return null;
        }
    }

    private boolean putToRedis(Long serviceId, String cacheKey, CachePayload payload, long ttlMillis) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return false;
        }
        try {
            redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(payload), ttlMillis, TimeUnit.MILLISECONDS);
            String serviceKeysKey = serviceKeysKey(serviceId);
            redisTemplate.opsForSet().add(serviceKeysKey, cacheKey);
            redisTemplate.expire(serviceKeysKey, SERVICE_KEYS_TTL_DAYS, TimeUnit.DAYS);
            log.debug("Data service response Redis cache put, key={}, ttlMillis={}", cacheKey, ttlMillis);
            return true;
        } catch (Exception ex) {
            log.warn("Failed to write data service response cache to Redis, fallback to local cache", ex);
            return false;
        }
    }

    private void evictRedisService(Long serviceId) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return;
        }
        String serviceKeysKey = serviceKeysKey(serviceId);
        try {
            Set<String> keys = redisTemplate.opsForSet().members(serviceKeysKey);
            int deletedCount = 0;
            if (keys != null && !keys.isEmpty()) {
                for (String key : keys) {
                    if (Boolean.TRUE.equals(redisTemplate.delete(key))) {
                        deletedCount++;
                    }
                }
            }
            redisTemplate.delete(serviceKeysKey);
            log.debug("Data service response Redis cache evicted, serviceId={}, keyCount={}, deletedCount={}",
                    serviceId, keys == null ? 0 : keys.size(), deletedCount);
        } catch (Exception ex) {
            log.warn("Failed to evict data service response cache from Redis", ex);
        }
    }

    private CacheLookup getFromLocal(String cacheKey) {
        LocalCacheEntry entry = localCache.get(cacheKey);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt <= System.currentTimeMillis()) {
            localCache.remove(cacheKey);
            return null;
        }
        return new CacheLookup(copyData(entry.data), entry.rowCount);
    }

    private void putToLocal(String cacheKey, CachePayload payload) {
        localCache.put(cacheKey, new LocalCacheEntry(copyData(payload.data), payload.rowCount, payload.expiresAt));
    }

    private String cacheKey(Long serviceId, String rawKey) {
        return KEY_PREFIX + serviceId + ":" + sha256(rawKey);
    }

    private String serviceKeysKey(Long serviceId) {
        return SERVICE_KEYS_PREFIX + serviceId;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : hash) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build cache key", ex);
        }
    }

    private Map<String, Object> copyData(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(source);
    }

    public static class CacheLookup {
        private final Map<String, Object> data;
        private final long rowCount;

        private CacheLookup(Map<String, Object> data, long rowCount) {
            this.data = data;
            this.rowCount = rowCount;
        }

        public Map<String, Object> getData() {
            return data;
        }

        public long getRowCount() {
            return rowCount;
        }
    }

    public static class CachePayload {
        public Map<String, Object> data;
        public long rowCount;
        public long expiresAt;
    }

    private static class LocalCacheEntry {
        private final Map<String, Object> data;
        private final long rowCount;
        private final long expiresAt;

        private LocalCacheEntry(Map<String, Object> data, long rowCount, long expiresAt) {
            this.data = data;
            this.rowCount = rowCount;
            this.expiresAt = expiresAt;
        }
    }
}
