package com.jdragon.studio.test;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.ClusterInstanceIdentity;
import com.jdragon.studio.infra.service.ClusterLockService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterLockServiceRegressionTest {

    @Test
    void shouldProvideNonReentrantLockForSameInstanceConcurrentGuards() {
        ClusterLockService lockService = lockService();

        assertThat(lockService.tryAcquire("lt_reg_s08_reentrant_lock", 60)).isTrue();
        assertThat(lockService.tryAcquire("lt_reg_s08_reentrant_lock", 60)).isTrue();

        assertThat(lockService.tryAcquireNonReentrant("lt_reg_s08_exclusive_lock", 60)).isTrue();
        assertThat(lockService.tryAcquireNonReentrant("lt_reg_s08_exclusive_lock", 60)).isFalse();
    }

    private ClusterLockService lockService() {
        Path databasePath;
        try {
            databasePath = Files.createTempFile("studio-cluster-lock-regression", ".db");
            databasePath.toFile().deleteOnExit();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create temporary SQLite database", e);
        }
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + databasePath.toAbsolutePath());
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute(
                "create table studio_cluster_lock (" +
                        "id integer primary key, " +
                        "lock_name varchar(255) not null unique, " +
                        "owner_id varchar(255), " +
                        "locked_until datetime, " +
                        "last_acquired_at datetime, " +
                        "created_at datetime, " +
                        "updated_at datetime)"
        );
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setInstanceId("lt-reg-s08-same-instance");
        return new ClusterLockService(jdbcTemplate, properties, new ClusterInstanceIdentity(properties));
    }
}
