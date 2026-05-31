package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.function.Supplier;

@Service
public class ClusterLockService {

    private static final long DEFAULT_LEASE_SECONDS = 120L;

    private final JdbcTemplate jdbcTemplate;
    private final StudioPlatformProperties properties;
    private final ClusterInstanceIdentity identity;

    public ClusterLockService(JdbcTemplate jdbcTemplate,
                              StudioPlatformProperties properties,
                              ClusterInstanceIdentity identity) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.identity = identity;
    }

    public boolean tryAcquire(String lockName) {
        return tryAcquire(lockName, resolveDefaultLeaseSeconds());
    }

    public boolean tryAcquire(String lockName, long leaseSeconds) {
        String normalizedLockName = normalizeLockName(lockName);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lockedUntil = now.plusSeconds(Math.max(1L, leaseSeconds));
        int updated = jdbcTemplate.update(
                "update studio_cluster_lock set owner_id=?, locked_until=?, last_acquired_at=?, updated_at=? " +
                        "where lock_name=? and (locked_until is null or locked_until < ? or owner_id=?)",
                identity.instanceId(), lockedUntil, now, now, normalizedLockName, now, identity.instanceId());
        if (updated > 0) {
            return true;
        }
        try {
            jdbcTemplate.update(
                    "insert into studio_cluster_lock (id, lock_name, owner_id, locked_until, last_acquired_at, created_at, updated_at) " +
                            "values (?, ?, ?, ?, ?, ?, ?)",
                    IdWorker.getId(), normalizedLockName, identity.instanceId(), lockedUntil, now, now, now);
            return true;
        } catch (DuplicateKeyException e) {
            return retryAcquire(normalizedLockName, now, lockedUntil);
        }
    }

    public void release(String lockName) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "update studio_cluster_lock set locked_until=?, updated_at=? where lock_name=? and owner_id=?",
                now, now, normalizeLockName(lockName), identity.instanceId());
    }

    public <T> T executeIfAcquired(String lockName, Supplier<T> action, Supplier<T> fallback) {
        if (!tryAcquire(lockName)) {
            return fallback.get();
        }
        try {
            return action.get();
        } finally {
            release(lockName);
        }
    }

    public void runIfAcquired(String lockName, Runnable action) {
        if (!tryAcquire(lockName)) {
            return;
        }
        try {
            action.run();
        } finally {
            release(lockName);
        }
    }

    private boolean retryAcquire(String lockName, LocalDateTime now, LocalDateTime lockedUntil) {
        int updated = jdbcTemplate.update(
                "update studio_cluster_lock set owner_id=?, locked_until=?, last_acquired_at=?, updated_at=? " +
                        "where lock_name=? and (locked_until is null or locked_until < ? or owner_id=?)",
                identity.instanceId(), lockedUntil, now, now, lockName, now, identity.instanceId());
        return updated > 0;
    }

    private long resolveDefaultLeaseSeconds() {
        Long configured = properties.getDispatch() == null ? null : properties.getDispatch().getClusterLockLeaseSeconds();
        return configured == null ? DEFAULT_LEASE_SECONDS : Math.max(1L, configured.longValue());
    }

    private String normalizeLockName(String lockName) {
        if (lockName == null || lockName.trim().isEmpty()) {
            throw new IllegalArgumentException("lockName must not be blank");
        }
        return lockName.trim();
    }
}
