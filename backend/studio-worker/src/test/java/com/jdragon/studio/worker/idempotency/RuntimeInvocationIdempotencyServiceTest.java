package com.jdragon.studio.worker.idempotency;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.config.MybatisPlusConfig;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.mapper.RuntimeInvocationIdempotencyMapper;
import com.jdragon.studio.infra.service.ClusterInstanceIdentity;
import com.jdragon.studio.infra.service.EncryptionService;
import com.jdragon.studio.infra.service.RuntimeInvocationFingerprintSupport;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeInvocationIdempotencyServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldReplayCompletedResponseAndRejectFingerprintReuse() throws Exception {
        Fixture fixture = fixture("replay.db");
        String keyHash = RuntimeInvocationFingerprintSupport.hashKey("public-key-never-persisted");
        String fingerprint = hex('a');

        RuntimeInvocationIdempotencyService.BeginResult first = fixture.service.begin(
                "tenant-a", 101L, 50L, "DATA_INGESTION_SERVICE", 32L, keyHash, fingerprint);
        assertEquals(RuntimeInvocationIdempotencyService.Action.EXECUTE, first.getAction());

        RuntimeInvocationIdempotencyService.BeginResult running = fixture.service.begin(
                "tenant-a", 101L, 50L, "DATA_INGESTION_SERVICE", 32L, keyHash, fingerprint);
        assertEquals(RuntimeInvocationIdempotencyService.ConflictReason.RUNNING,
                running.getConflictReason());

        byte[] response = "{\"status\":\"SUCCESS\"}".getBytes(StandardCharsets.UTF_8);
        fixture.service.complete(first.getGuardId(), first.getOwnerToken(),
                200, "application/json;charset=UTF-8", response);
        RuntimeInvocationIdempotencyService.BeginResult replay = fixture.service.begin(
                "tenant-a", 101L, 50L, "DATA_INGESTION_SERVICE", 32L, keyHash, fingerprint);
        assertEquals(RuntimeInvocationIdempotencyService.Action.REPLAY, replay.getAction());
        assertEquals(200, replay.getStoredResponse().getStatus());
        assertArrayEquals(response, replay.getStoredResponse().getBody());

        RuntimeInvocationIdempotencyService.BeginResult mismatch = fixture.service.begin(
                "tenant-a", 101L, 50L, "DATA_INGESTION_SERVICE", 32L, keyHash, hex('b'));
        assertEquals(RuntimeInvocationIdempotencyService.ConflictReason.FINGERPRINT_MISMATCH,
                mismatch.getConflictReason());

        try (Connection connection = fixture.dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select key_hash,owner_token_hash,response_body_ciphertext,status,version " +
                     "from studio_runtime_idempotency")) {
            rows.next();
            assertEquals(keyHash, rows.getString("key_hash"));
            assertNotEquals(first.getOwnerToken(), rows.getString("owner_token_hash"));
            assertFalse(rows.getString("response_body_ciphertext").contains("SUCCESS"));
            assertEquals(RuntimeInvocationIdempotencyService.STATUS_COMPLETED, rows.getString("status"));
            assertEquals(1, rows.getInt("version"));
        }
    }

    @Test
    void shouldUseUniqueGuardUnderConcurrentBegin() throws Exception {
        Fixture fixture = fixture("concurrent.db");
        String keyHash = hex('c');
        String fingerprint = hex('d');
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<RuntimeInvocationIdempotencyService.BeginResult>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return fixture.service.begin("tenant-a", 101L, 50L,
                            "PROTOCOL_CONVERSION_SERVICE", 33L, keyHash, fingerprint);
                }));
            }
            ready.await();
            start.countDown();

            int executeCount = 0;
            int runningCount = 0;
            for (Future<RuntimeInvocationIdempotencyService.BeginResult> future : futures) {
                RuntimeInvocationIdempotencyService.BeginResult result = future.get();
                if (result.getAction() == RuntimeInvocationIdempotencyService.Action.EXECUTE) {
                    executeCount++;
                } else if (result.getConflictReason()
                        == RuntimeInvocationIdempotencyService.ConflictReason.RUNNING) {
                    runningCount++;
                }
            }
            assertEquals(1, executeCount);
            assertEquals(1, runningCount);
            assertEquals(1, count(fixture, "select count(*) from studio_runtime_idempotency"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRequireOwnerCasAndKeepUnknownRequestsClosedToTakeover() throws Exception {
        Fixture fixture = fixture("owner.db");
        RuntimeInvocationIdempotencyService.BeginResult first = fixture.service.begin(
                "tenant-a", 101L, 50L, "DATA_INGESTION_SERVICE", 32L, hex('e'), hex('f'));

        assertThrows(StudioException.class, () -> fixture.service.complete(
                first.getGuardId(), "wrong-owner", 200, "application/json", new byte[0]));
        fixture.service.markUnknown(first.getGuardId(), first.getOwnerToken());

        RuntimeInvocationIdempotencyService.BeginResult duplicate = fixture.service.begin(
                "tenant-a", 101L, 50L, "DATA_INGESTION_SERVICE", 32L, hex('e'), hex('f'));
        assertEquals(RuntimeInvocationIdempotencyService.Action.CONFLICT, duplicate.getAction());
        assertEquals(RuntimeInvocationIdempotencyService.ConflictReason.UNKNOWN,
                duplicate.getConflictReason());
        assertEquals(1, count(fixture, "select count(*) from studio_runtime_idempotency"));
    }

    @Test
    void shouldRefuseOversizedReplayPayloadWithoutCompletingGuard() throws Exception {
        Fixture fixture = fixture("limit.db");
        fixture.properties.getRuntimeInvocationIdempotency().setMaxResponseBytes(1024);
        RuntimeInvocationIdempotencyService.BeginResult first = fixture.service.begin(
                "tenant-a", 101L, 50L, "PROTOCOL_CONVERSION_SERVICE", 33L, hex('1'), hex('2'));

        assertThrows(StudioException.class, () -> fixture.service.complete(
                first.getGuardId(), first.getOwnerToken(), 200, "application/json", new byte[1025]));
        assertEquals(RuntimeInvocationIdempotencyService.STATUS_RUNNING,
                scalar(fixture, "select status from studio_runtime_idempotency"));
    }

    private Fixture fixture(String fileName) throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve(fileName).toAbsolutePath() + "?busy_timeout=5000";
        UnpooledDataSource dataSource = new UnpooledDataSource("org.sqlite.JDBC", url, null);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("pragma journal_mode=WAL");
            statement.execute("create table studio_runtime_idempotency (" +
                    "id integer primary key,tenant_id text,project_id integer,deleted integer default 0," +
                    "created_at text,updated_at text,runtime_cluster_id integer,resource_type text,resource_id integer," +
                    "key_hash text,request_fingerprint text,status text,owner_token_hash text,owner_instance_id text," +
                    "owner_boot_id text,response_status integer,response_content_type text,response_body_ciphertext text," +
                    "completed_at text,version integer default 0)");
            statement.execute("create unique index uk_runtime_idem_scope_key on studio_runtime_idempotency" +
                    "(tenant_id,project_id,resource_type,resource_id,key_hash)");
        }

        Environment environment = new Environment("runtime-idempotency-test",
                new JdbcTransactionFactory(), dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(RuntimeInvocationIdempotencyMapper.class);
        configuration.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor());
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        RuntimeInvocationIdempotencyMapper mapper =
                new SqlSessionTemplate(factory).getMapper(RuntimeInvocationIdempotencyMapper.class);
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setEncryptionSecret("idempotency-test-secret");
        properties.setInstanceId("worker-test-1");
        ClusterInstanceIdentity identity = new ClusterInstanceIdentity(properties);
        RuntimeInvocationIdempotencyService service = new RuntimeInvocationIdempotencyService(
                mapper, new EncryptionService(properties), identity, properties);
        return new Fixture(service, dataSource, properties);
    }

    private int count(Fixture fixture, String sql) throws Exception {
        return Integer.parseInt(scalar(fixture, sql));
    }

    private String scalar(Fixture fixture, String sql) throws Exception {
        try (Connection connection = fixture.dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private String hex(char value) {
        return String.valueOf(value).repeat(64);
    }

    private static final class Fixture {
        private final RuntimeInvocationIdempotencyService service;
        private final UnpooledDataSource dataSource;
        private final StudioPlatformProperties properties;

        private Fixture(RuntimeInvocationIdempotencyService service,
                        UnpooledDataSource dataSource,
                        StudioPlatformProperties properties) {
            this.service = service;
            this.dataSource = dataSource;
            this.properties = properties;
        }
    }
}
