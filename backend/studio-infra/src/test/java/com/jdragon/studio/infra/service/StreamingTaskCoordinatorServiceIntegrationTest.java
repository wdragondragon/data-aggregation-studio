package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.jdragon.studio.dto.enums.DispatchExecutionType;
import com.jdragon.studio.dto.enums.StreamingObservedState;
import com.jdragon.studio.infra.config.MybatisPlusConfig;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.StreamTaskAttemptEntity;
import com.jdragon.studio.infra.entity.StreamTaskDeployEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunLogChunkMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.StreamMetricBucketMapper;
import com.jdragon.studio.infra.mapper.StreamTaskAttemptMapper;
import com.jdragon.studio.infra.mapper.StreamTaskDeployMapper;
import com.jdragon.studio.infra.mapper.StreamTaskEventMapper;
import com.jdragon.studio.infra.mapper.StreamTaskRunMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StreamingTaskCoordinatorServiceIntegrationTest {

    @Test
    void concurrentReconcileCreatesOneAttemptAndOneStreamingDispatch() throws Exception {
        try (Fixture fixture = fixture()) {
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> first = executor.submit(() -> reconcileAfter(start, fixture.coordinator));
                Future<Integer> second = executor.submit(() -> reconcileAfter(start, fixture.coordinator));
                start.countDown();
                first.get();
                second.get();
            } finally {
                executor.shutdownNow();
            }

            assertEquals(1, fixture.count("select count(*) from stream_task_attempt"));
            assertEquals(1, fixture.count("select count(*) from dispatch_task"));
            assertEquals(0, fixture.coordinator.reconcileDeployments());
            StreamTaskAttemptEntity attempt = fixture.currentAttempt();
            DispatchTaskEntity dispatch = fixture.dispatchMapper.selectById(attempt.getDispatchTaskId());
            assertEquals(1, attempt.getAttemptNo());
            assertEquals(DispatchExecutionType.STREAMING_COLLECTION_TASK.name(), dispatch.getExecutionType());
            assertEquals(1, ((Number) dispatch.getPayloadJson().get("streamAttemptNo")).intValue());
            assertFalse(dispatch.getPayloadJson().containsKey("password"));
        }
    }

    @Test
    void offlineBeforeWorkerClaimStopsQueuedAttemptAndDoesNotRecover() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.coordinator.reconcileDeployments();
            StreamTaskAttemptEntity attempt = fixture.currentAttempt();
            fixture.jdbc.update("update stream_task_deploy set desired_state='STOPPED', observed_state='STOPPING' "
                    + "where id=?", fixture.deployment().getId());

            assertEquals(1, fixture.coordinator.reconcileDeployments());

            StreamTaskDeployEntity stopped = fixture.deployment();
            assertEquals(StreamingObservedState.STOPPED.name(), stopped.getObservedState());
            assertNull(stopped.getCurrentAttemptId());
            assertEquals("STOPPED", fixture.attemptMapper.selectById(attempt.getId()).getStatus());
            assertEquals("SUCCESS", fixture.dispatchMapper.selectById(attempt.getDispatchTaskId()).getStatus());
            assertEquals(1, fixture.count("select count(*) from stream_task_attempt"));
            assertEquals(0, fixture.coordinator.reconcileDeployments());
        }
    }

    @Test
    void workerRestartCreatesNextAttemptFromSameLogicalRun() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.coordinator.reconcileDeployments();
            StreamTaskAttemptEntity first = fixture.currentAttempt();
            DispatchTaskEntity dispatch = fixture.claim(first);
            assertTrue(fixture.coordinator.workerStarted(first.getId(), dispatch.getId(), 901L,
                    "worker-instance-1", "worker-boot-1"));

            fixture.coordinator.workerRestartInterrupted(dispatch);

            StreamTaskDeployEntity recovering = fixture.deployment();
            assertEquals(StreamingObservedState.RECOVERING.name(), recovering.getObservedState());
            assertEquals(1, recovering.getConsecutiveFailureCount());
            assertNull(recovering.getCurrentAttemptId());
            assertEquals("INTERRUPTED", fixture.attemptMapper.selectById(first.getId()).getStatus());

            fixture.jdbc.update("update stream_task_deploy set next_retry_at=null where id=?", recovering.getId());
            fixture.coordinator.reconcileDeployments();
            StreamTaskAttemptEntity second = fixture.currentAttempt();
            assertEquals(first.getRunId(), second.getRunId());
            assertEquals(2, second.getAttemptNo());
            DispatchTaskEntity retryDispatch = fixture.dispatchMapper.selectById(second.getDispatchTaskId());
            assertEquals(2, ((Number) retryDispatch.getPayloadJson().get("streamAttemptNo")).intValue());
        }
    }

    @Test
    void committedBatchPersistsCheckpointAndClearsFailureCount() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.coordinator.reconcileDeployments();
            StreamTaskAttemptEntity attempt = fixture.currentAttempt();
            DispatchTaskEntity dispatch = fixture.claim(attempt);
            assertTrue(fixture.coordinator.workerStarted(attempt.getId(), dispatch.getId(), 902L,
                    "worker-instance-1", "worker-boot-1"));
            fixture.jdbc.update("update stream_task_deploy set consecutive_failure_count=4 where id=?",
                    fixture.deployment().getId());

            Map<String, Object> checkpoint = Map.of(
                    "batchId", "NativeStreaming-M4-batch-1",
                    "state", Map.of("nextOffsets", Map.of("NativeStreaming-M4:0", 11L)));
            assertTrue(fixture.coordinator.batchCommitted(attempt.getId(), checkpoint));

            StreamTaskDeployEntity deployment = fixture.deployment();
            StreamTaskAttemptEntity committed = fixture.attemptMapper.selectById(attempt.getId());
            assertEquals(0, deployment.getConsecutiveFailureCount());
            assertEquals("NativeStreaming-M4-batch-1", deployment.getLastCheckpointJson().get("batchId"));
            assertEquals(1L, committed.getCommittedBatchCount());
        }
    }

    @Test
    void tenConsecutiveFailuresPauseUntilExplicitRecover() throws Exception {
        try (Fixture fixture = fixture()) {
            for (int index = 1; index <= 10; index++) {
                fixture.coordinator.reconcileDeployments();
                StreamTaskAttemptEntity attempt = fixture.currentAttempt();
                assertEquals(index, attempt.getAttemptNo());
                fixture.coordinator.attemptFailed(attempt.getId(), "NATIVE_STREAMING_M4_FAILURE",
                        "expected failure " + index);
                if (index < 10) {
                    fixture.jdbc.update("update stream_task_deploy set next_retry_at=null where id=?",
                            fixture.deployment().getId());
                }
            }

            StreamTaskDeployEntity failed = fixture.deployment();
            assertEquals(StreamingObservedState.FAILED.name(), failed.getObservedState());
            assertEquals(10, failed.getConsecutiveFailureCount());
            assertNull(failed.getCurrentAttemptId());
            assertNull(failed.getNextRetryAt());
            assertEquals(0, fixture.coordinator.reconcileDeployments());
            assertEquals(10, fixture.count("select count(*) from stream_task_attempt"));

            fixture.runtimeService.recover(fixture.task());
            fixture.coordinator.reconcileDeployments();
            StreamTaskAttemptEntity recovered = fixture.currentAttempt();
            assertEquals(11, recovered.getAttemptNo());
            assertEquals(2L, recovered.getGeneration());
            assertEquals(0, fixture.deployment().getConsecutiveFailureCount());
        }
    }

    private int reconcileAfter(CountDownLatch start,
                               StreamingTaskCoordinatorService coordinator) throws Exception {
        start.await();
        return coordinator.reconcileDeployments();
    }

    private Fixture fixture() throws Exception {
        Path database = Files.createTempFile("studio-stream-coordinator-", ".sqlite");
        String url = "jdbc:sqlite:" + database.toAbsolutePath() + "?busy_timeout=10000";
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            statement.execute("pragma journal_mode=WAL");
            createCoreTables(statement);
            statement.executeUpdate("insert into collection_task_definition "
                    + "(id, tenant_id, project_id, deleted, created_at, updated_at, runtime_cluster_id, name, "
                    + "task_type, status, execution_mode, source_count, streaming_options_json) values "
                    + "(100, 'default', 20, 0, datetime('now'), datetime('now'), 30, 'NativeStreaming-M4', "
                    + "'SINGLE_TABLE', 'ONLINE', 'STREAMING', 1, "
                    + "'{\"groupId\":\"studio.default.100\",\"maxConsecutiveFailures\":10,"
                    + "\"retryInitialDelayMs\":1,\"retryMaxDelayMs\":1,\"resetOffset\":true}')");
            JdbcTemplate jdbc = new JdbcTemplate(new UnpooledDataSource("org.sqlite.JDBC", url, null));
            new StudioStreamingSchemaUpgradeSupport(jdbc, new StudioSchemaIntrospector(jdbc)).ensureSqlite();
            statement.executeUpdate("insert into stream_task_run "
                    + "(id, tenant_id, project_id, deleted, collection_task_id, generation, runtime_cluster_id, "
                    + "status, delivery_semantics, group_id, started_by, started_at, final_checkpoint_json, "
                    + "created_at, updated_at) values "
                    + "(300, 'default', 20, 0, 100, 1, 30, 'RUNNING', 'AT_LEAST_ONCE', "
                    + "'studio.default.100', 7, datetime('now'), '{}', datetime('now'), datetime('now'))");
            statement.executeUpdate("insert into stream_task_deploy "
                    + "(id, tenant_id, project_id, deleted, collection_task_id, runtime_cluster_id, generation, "
                    + "desired_state, observed_state, current_run_id, consecutive_failure_count, "
                    + "last_checkpoint_json, version, created_at, updated_at) values "
                    + "(200, 'default', 20, 0, 100, 30, 1, 'RUNNING', 'RECOVERING', 300, 0, '{}', 0, "
                    + "datetime('now'), datetime('now'))");
        }

        UnpooledDataSource dataSource = new UnpooledDataSource("org.sqlite.JDBC", url, null);
        SqlSessionTemplate template = new SqlSessionTemplate(sessionFactory(dataSource));
        RuntimeResourceRevisionService revisionService = mock(RuntimeResourceRevisionService.class);
        when(revisionService.collectionTaskRevision(eq(100L), any(LocalDateTime.class)))
                .thenReturn("revision-1");
        StreamTaskDeployMapper deployMapper = template.getMapper(StreamTaskDeployMapper.class);
        StreamTaskRunMapper runMapper = template.getMapper(StreamTaskRunMapper.class);
        StreamTaskAttemptMapper attemptMapper = template.getMapper(StreamTaskAttemptMapper.class);
        StreamTaskEventMapper eventMapper = template.getMapper(StreamTaskEventMapper.class);
        DispatchTaskMapper dispatchMapper = template.getMapper(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = template.getMapper(RunRecordMapper.class);
        WorkerLeaseMapper workerLeaseMapper = template.getMapper(WorkerLeaseMapper.class);
        CollectionTaskDefinitionMapper taskMapper = template.getMapper(CollectionTaskDefinitionMapper.class);
        StreamingTaskCoordinatorService coordinator = new StreamingTaskCoordinatorService(
                deployMapper, runMapper, attemptMapper, eventMapper, dispatchMapper,
                runRecordMapper, workerLeaseMapper, taskMapper, revisionService);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("default");
        when(security.currentUserId()).thenReturn(7L);
        StreamingTaskRuntimeService runtimeService = new StreamingTaskRuntimeService(
                deployMapper, runMapper, attemptMapper,
                template.getMapper(StreamMetricBucketMapper.class), eventMapper,
                template.getMapper(RunLogChunkMapper.class), security);
        return new Fixture(database, new JdbcTemplate(dataSource), template, taskMapper,
                deployMapper, attemptMapper, dispatchMapper, coordinator, runtimeService);
    }

    private void createCoreTables(Statement statement) throws Exception {
        statement.execute("create table collection_task_definition ("
                + "id integer primary key, tenant_id text, project_id integer, deleted integer default 0, "
                + "created_at text, updated_at text, created_by integer, runtime_cluster_id integer, name text, "
                + "task_type text, status text, execution_mode text, source_count integer, "
                + "target_datasource_name_snapshot text, target_datasource_type_code_snapshot text, "
                + "target_model_name_snapshot text, target_model_physical_locator_snapshot text, "
                + "source_bindings_json text, target_binding_json text, field_mappings_json text, "
                + "execution_options_json text, streaming_options_json text)");
        statement.execute("create table dispatch_task ("
                + "id integer primary key, tenant_id text, project_id integer, deleted integer default 0, "
                + "created_at text, updated_at text, execution_type text, workflow_run_id integer, "
                + "workflow_definition_id integer, workflow_version_id integer, collection_task_id integer, "
                + "quality_task_id integer, file_transfer_task_id integer, file_transfer_run_id integer, "
                + "triggered_by_user_id integer, run_record_id integer, node_code text, status text, "
                + "termination_requested integer default 0, target_cluster_id integer, resource_revision text, "
                + "claim_token text, worker_boot_id text, worker_group_code text, lease_owner text, "
                + "worker_instance_id text, lease_expires_at text, scheduled_fire_time text, attempts integer, "
                + "max_retries integer, protected_payload_ciphertext text, payload_json text)");
        statement.execute("create table run_record ("
                + "id integer primary key, tenant_id text, project_id integer, deleted integer default 0, "
                + "created_at text, updated_at text, status text, ended_at text, message text, result_json text)");
        statement.execute("create table worker_lease ("
                + "id integer primary key, tenant_id text, deleted integer default 0, created_at text, "
                + "updated_at text, runtime_cluster_id integer, runtime_cluster_code text, worker_group_code text, "
                + "worker_code text, worker_kind text, instance_id text, boot_id text, runtime_version text, "
                + "plugin_fingerprint text, host_name text, pod_name text, node_name text, status text, "
                + "last_heartbeat_at text, lease_expires_at text, capabilities_json text)");
    }

    private SqlSessionFactory sessionFactory(UnpooledDataSource dataSource) {
        Environment environment = new Environment(
                "stream-coordinator-test", new JdbcTransactionFactory(), dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig()
                .setDbConfig(new GlobalConfig.DbConfig())
                .setMetaObjectHandler(new MybatisPlusConfig().metaObjectHandler()));
        configuration.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor());
        List.of(CollectionTaskDefinitionMapper.class, StreamTaskDeployMapper.class,
                StreamTaskRunMapper.class, StreamTaskAttemptMapper.class, StreamTaskEventMapper.class,
                StreamMetricBucketMapper.class, RunLogChunkMapper.class, DispatchTaskMapper.class,
                RunRecordMapper.class, WorkerLeaseMapper.class).forEach(configuration::addMapper);
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private static final class Fixture implements AutoCloseable {
        private final Path database;
        private final JdbcTemplate jdbc;
        private final SqlSessionTemplate template;
        private final CollectionTaskDefinitionMapper taskMapper;
        private final StreamTaskDeployMapper deployMapper;
        private final StreamTaskAttemptMapper attemptMapper;
        private final DispatchTaskMapper dispatchMapper;
        private final StreamingTaskCoordinatorService coordinator;
        private final StreamingTaskRuntimeService runtimeService;

        private Fixture(Path database,
                        JdbcTemplate jdbc,
                        SqlSessionTemplate template,
                        CollectionTaskDefinitionMapper taskMapper,
                        StreamTaskDeployMapper deployMapper,
                        StreamTaskAttemptMapper attemptMapper,
                        DispatchTaskMapper dispatchMapper,
                        StreamingTaskCoordinatorService coordinator,
                        StreamingTaskRuntimeService runtimeService) {
            this.database = database;
            this.jdbc = jdbc;
            this.template = template;
            this.taskMapper = taskMapper;
            this.deployMapper = deployMapper;
            this.attemptMapper = attemptMapper;
            this.dispatchMapper = dispatchMapper;
            this.coordinator = coordinator;
            this.runtimeService = runtimeService;
        }

        private CollectionTaskDefinitionEntity task() {
            return taskMapper.selectById(100L);
        }

        private StreamTaskDeployEntity deployment() {
            return deployMapper.selectById(200L);
        }

        private StreamTaskAttemptEntity currentAttempt() {
            StreamTaskDeployEntity deployment = deployment();
            assertNotNull(deployment.getCurrentAttemptId());
            return attemptMapper.selectById(deployment.getCurrentAttemptId());
        }

        private DispatchTaskEntity claim(StreamTaskAttemptEntity attempt) {
            DispatchTaskEntity dispatch = dispatchMapper.selectById(attempt.getDispatchTaskId());
            dispatch.setStatus("RUNNING");
            dispatch.setClaimToken("claim-" + attempt.getAttemptNo());
            dispatch.setWorkerInstanceId("worker-instance-1");
            dispatch.setWorkerBootId("worker-boot-1");
            dispatch.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(1));
            dispatchMapper.updateById(dispatch);
            return dispatchMapper.selectById(dispatch.getId());
        }

        private int count(String sql) {
            Integer value = jdbc.queryForObject(sql, Integer.class);
            return value == null ? 0 : value.intValue();
        }

        @Override
        public void close() throws Exception {
            template.clearCache();
            deleteOrSchedule(Path.of(database.toString() + "-wal"));
            deleteOrSchedule(Path.of(database.toString() + "-shm"));
            deleteOrSchedule(database);
        }

        private void deleteOrSchedule(Path path) throws Exception {
            try {
                Files.deleteIfExists(path);
            } catch (java.nio.file.FileSystemException exception) {
                path.toFile().deleteOnExit();
            }
        }
    }
}
