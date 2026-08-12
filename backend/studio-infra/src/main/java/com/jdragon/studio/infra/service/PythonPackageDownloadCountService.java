package com.jdragon.studio.infra.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * Tracks the global "today" Python package download count in Redis. The key embeds the local
 * date, so the count naturally resets at midnight when a new date's key is used; no scheduled
 * job is required. When Redis is unavailable the counter degrades to 0 rather than failing the
 * download request.
 */
@Service
public class PythonPackageDownloadCountService {

    private static final Logger log = LoggerFactory.getLogger(PythonPackageDownloadCountService.class);

    private static final String KEY_PREFIX = "studio:python-package:download:today:";
    private static final long KEY_TTL_HOURS = 48L;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    public PythonPackageDownloadCountService(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.redisTemplateProvider = redisTemplateProvider;
    }

    /**
     * Increments the today counter and returns the new value. Returns 0 when Redis is unavailable.
     */
    public long incrementToday() {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            log.debug("Python package download counter bypassed: Redis template is not available");
            return 0L;
        }
        String key = todayKey();
        try {
            Long value = redisTemplate.opsForValue().increment(key);
            redisTemplate.expire(key, KEY_TTL_HOURS, TimeUnit.HOURS);
            return value == null ? 0L : value.longValue();
        } catch (Exception ex) {
            log.warn("Failed to increment Python package download counter, bypassed", ex);
            return 0L;
        }
    }

    /**
     * Returns the today download count (0 when no downloads yet or Redis is unavailable).
     */
    public long getToday() {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            log.debug("Python package download counter read bypassed: Redis template is not available");
            return 0L;
        }
        String key = todayKey();
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value == null || value.trim().isEmpty()) {
                return 0L;
            }
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            log.warn("Python package download counter value is not a number, key={}", key);
            return 0L;
        } catch (Exception ex) {
            log.warn("Failed to read Python package download counter, bypassed", ex);
            return 0L;
        }
    }

    private String todayKey() {
        return KEY_PREFIX + LocalDate.now().format(DATE_FORMAT);
    }
}
