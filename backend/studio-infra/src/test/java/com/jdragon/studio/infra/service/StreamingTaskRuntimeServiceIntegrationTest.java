package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.CollectionTaskExecutionMode;
import com.jdragon.studio.dto.enums.StreamingDesiredState;
import com.jdragon.studio.dto.enums.StreamingObservedState;
import com.jdragon.studio.dto.model.CollectionTaskStreamingOptions;
import com.jdragon.studio.dto.model.CollectionTaskStreamingRuntimeView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.StreamingMetricBucketView;
import com.jdragon.studio.infra.config.MybatisPlusConfig;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.StreamMetricBucketEntity;
import com.jdragon.studio.infra.entity.StreamTaskDeployEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.RunLogChunkMapper;
import com.jdragon.studio.infra.mapper.StreamMetricBucketMapper;
import com.jdragon.studio.infra.mapper.StreamTaskAttemptMapper;
import com.jdragon.studio.infra.mapper.StreamTaskDeployMapper;
import com.jdragon.studio.infra.mapper.StreamTaskEventMapper;
import com.jdragon.studio.infra.mapper.StreamTaskRunMapper;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StreamingTaskRuntimeServiceIntegrationTest {

    @Test
    void lifecycleIsIdempotentAndCreatesNoPhysicalAttemptInM3() throws Exception {
        try (Fixture fixture = fixture()) {
            CollectionTaskDefinitionEntity task = fixture.task();
            fixture.service.ensureDeployment(task);

            CollectionTaskStreamingRuntimeView stopped = fixture.service.runtime(task);
            assertEquals(StreamingDesiredState.STOPPED, stopped.getDeployment().getDesiredState());
            assertEquals(StreamingObservedState.STOPPED, stopped.getDeployment().getObservedState());
            assertNull(stopped.getCurrentAttempt());

            CollectionTaskStreamingRuntimeView firstOnline = fixture.service.online(task);
            Long firstRunId = firstOnline.getDeployment().getCurrentRunId();
            assertNotNull(firstRunId);
            assertEquals(1L, firstOnline.getDeployment().getGeneration());
            assertEquals("studio.default.100", firstOnline.getCurrentRun().getGroupId());
            fixture.service.online(task);
            assertEquals(1, fixture.count("select count(*) from stream_task_run"));
            assertEquals(0, fixture.count("select count(*) from stream_task_attempt"));

            fixture.service.offline(task);
            fixture.service.offline(task);
            CollectionTaskStreamingRuntimeView offline = fixture.service.runtime(task);
            assertEquals(StreamingDesiredState.STOPPED, offline.getDeployment().getDesiredState());
            assertEquals(StreamingObservedState.STOPPED, offline.getDeployment().getObservedState());
            assertEquals("STOPPED", offline.getCurrentRun().getStatus());

            CollectionTaskStreamingRuntimeView secondOnline = fixture.service.online(task);
            assertEquals(3L, secondOnline.getDeployment().getGeneration());
            assertEquals(2, fixture.count("select count(*) from stream_task_run"));
            assertEquals(0, fixture.count("select count(*) from stream_task_attempt"));
        }
    }

    @Test
    void concurrentOnlineCreatesOnlyOneLogicalRun() throws Exception {
        try (Fixture fixture = fixture()) {
            CollectionTaskDefinitionEntity task = fixture.task();
            fixture.service.ensureDeployment(task);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = executor.submit(() -> awaitAndOnline(start, fixture.service, task));
                Future<?> second = executor.submit(() -> awaitAndOnline(start, fixture.service, task));
                start.countDown();
                try {
                    first.get();
                } finally {
                    second.get();
                }
            } finally {
                executor.shutdownNow();
            }

            assertEquals(1, fixture.count("select count(*) from stream_task_run"));
            assertEquals(0, fixture.count("select count(*) from stream_task_attempt"));
            assertEquals(1L, fixture.service.runtime(task).getDeployment().getGeneration());
        }
    }

    @Test
    void concurrentOnlineAndOfflineNeverCreatesDuplicateRunOrAttempt() throws Exception {
        try (Fixture fixture = fixture()) {
            CollectionTaskDefinitionEntity task = fixture.task();
            fixture.service.ensureDeployment(task);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<?> online = executor.submit(() -> awaitAndOnline(start, fixture.service, task));
                Future<?> offline = executor.submit(() -> awaitAndOffline(start, fixture.service, task));
                start.countDown();
                try {
                    online.get();
                } finally {
                    offline.get();
                }
            } finally {
                executor.shutdownNow();
            }

            CollectionTaskStreamingRuntimeView runtime = fixture.service.runtime(task);
            assertEquals(1, fixture.count("select count(*) from stream_task_run"));
            assertEquals(0, fixture.count("select count(*) from stream_task_attempt"));
            assertNotNull(runtime.getDeployment().getCurrentRunId());
            assertEquals(runtime.getDeployment().getCurrentRunId(), runtime.getCurrentRun().getId());
        }
    }

    @Test
    void recoverRequiresRunningFailedDeploymentAndResetsFailureState() throws Exception {
        try (Fixture fixture = fixture()) {
            CollectionTaskDefinitionEntity task = fixture.task();
            fixture.service.ensureDeployment(task);
            assertThrows(StudioException.class, () -> fixture.service.recover(task));

            fixture.service.online(task);
            StreamTaskDeployEntity deployment = fixture.deployMapper.selectById(
                    fixture.service.runtime(task).getDeployment().getId());
            deployment.setObservedState(StreamingObservedState.FAILED.name());
            deployment.setConsecutiveFailureCount(10);
            deployment.setLastErrorCode("TEST_FAILURE");
            deployment.setLastErrorSummary("failure summary");
            assertEquals(1, fixture.deployMapper.updateById(deployment));

            CollectionTaskStreamingRuntimeView recovered = fixture.service.recover(task);
            assertEquals(StreamingDesiredState.RUNNING, recovered.getDeployment().getDesiredState());
            assertEquals(StreamingObservedState.RECOVERING, recovered.getDeployment().getObservedState());
            assertEquals(0, recovered.getDeployment().getConsecutiveFailureCount());
            assertNull(recovered.getDeployment().getLastErrorCode());
            assertEquals(2L, recovered.getDeployment().getGeneration());
        }
    }

    @Test
    void metricsAggregateCounterDeltasAcrossAttemptsForSameMinute() throws Exception {
        try (Fixture fixture = fixture()) {
            CollectionTaskDefinitionEntity task = fixture.task();
            LocalDateTime bucket = LocalDateTime.of(2026, 8, 27, 7, 9);
            StreamMetricBucketEntity firstAttempt = metric(201L, 301L, bucket,
                    3L, 3L, 0L, 1L, 113L, 1L, 2L, 7L, 7L,
                    LocalDateTime.of(2026, 8, 27, 7, 9, 10),
                    LocalDateTime.of(2026, 8, 27, 7, 9, 20), 1L);
            StreamMetricBucketEntity recoveredAttempt = metric(202L, 302L, bucket,
                    2L, 2L, 1L, 0L, 71L, 1L, 1L, 3L, 5L,
                    LocalDateTime.of(2026, 8, 27, 7, 9, 40),
                    LocalDateTime.of(2026, 8, 27, 7, 9, 50), 2L);
            fixture.template.getMapper(StreamMetricBucketMapper.class).insert(firstAttempt);
            fixture.template.getMapper(StreamMetricBucketMapper.class).insert(recoveredAttempt);

            List<StreamingMetricBucketView> metrics = fixture.service.metrics(task, null, null);

            assertEquals(1, metrics.size());
            StreamingMetricBucketView aggregate = metrics.get(0);
            assertEquals(5L, aggregate.getRecordsRead());
            assertEquals(5L, aggregate.getWriteSucceedRecords());
            assertEquals(1L, aggregate.getWriteFailedRecords());
            assertEquals(1L, aggregate.getDirtyRecords());
            assertEquals(184L, aggregate.getBytesRead());
            assertEquals(2L, aggregate.getBatchCount());
            assertEquals(3L, aggregate.getRetryCount());
            assertEquals(3L, aggregate.getCurrentLag());
            assertEquals(7L, aggregate.getMaxLag());
            assertEquals(3L, aggregate.getRebalanceCount());
            assertEquals(302L, aggregate.getAttemptId());
            assertEquals(LocalDateTime.of(2026, 8, 27, 7, 9, 40), aggregate.getLastMessageAt());
            assertEquals(LocalDateTime.of(2026, 8, 27, 7, 9, 50), aggregate.getLastCheckpointAt());
        }
    }

    @Test
    void metricsPageAggregatesBeforePagingAndReturnsNewestMinuteFirst() throws Exception {
        try (Fixture fixture = fixture()) {
            CollectionTaskDefinitionEntity task = fixture.task();
            LocalDateTime oldest = LocalDateTime.of(2026, 8, 27, 7, 10);
            LocalDateTime middle = oldest.plusMinutes(1);
            LocalDateTime newest = oldest.plusMinutes(2);
            fixture.template.getMapper(StreamMetricBucketMapper.class).insert(
                    metric(301L, 401L, oldest, 1L, 1L, 0L, 0L, 10L, 1L, 0L, 0L, 0L, null, null, 0L));
            fixture.template.getMapper(StreamMetricBucketMapper.class).insert(
                    metric(302L, 402L, middle, 2L, 2L, 0L, 0L, 20L, 1L, 0L, 0L, 0L, null, null, 0L));
            fixture.template.getMapper(StreamMetricBucketMapper.class).insert(
                    metric(303L, 403L, newest, 3L, 3L, 0L, 0L, 30L, 1L, 0L, 0L, 0L, null, null, 0L));
            // A second attempt in the newest minute must be aggregated before the
            // page boundary is applied.
            fixture.template.getMapper(StreamMetricBucketMapper.class).insert(
                    metric(304L, 404L, newest, 4L, 4L, 0L, 0L, 40L, 1L, 0L, 0L, 0L, null, null, 0L));

            PageView<StreamingMetricBucketView> firstPage = fixture.service.metricsPage(task, null, null, 1, 2);
            assertEquals(3L, firstPage.getTotal());
            assertEquals(2, firstPage.getItems().size());
            assertEquals(newest, firstPage.getItems().get(0).getBucketStart());
            assertEquals(7L, firstPage.getItems().get(0).getRecordsRead());
            assertEquals(middle, firstPage.getItems().get(1).getBucketStart());

            PageView<StreamingMetricBucketView> secondPage = fixture.service.metricsPage(task, null, null, 2, 2);
            assertEquals(3L, secondPage.getTotal());
            assertEquals(1, secondPage.getItems().size());
            assertEquals(oldest, secondPage.getItems().get(0).getBucketStart());
        }
    }

    @Test
    void metricsPageCanFilterEmptyMinutesBeforeCountingAndPaging() throws Exception {
        try (Fixture fixture = fixture()) {
            CollectionTaskDefinitionEntity task = fixture.task();
            LocalDateTime first = LocalDateTime.of(2026, 8, 27, 8, 0);
            LocalDateTime second = first.plusMinutes(1);
            LocalDateTime third = first.plusMinutes(2);
            fixture.template.getMapper(StreamMetricBucketMapper.class).insert(
                    metric(501L, 601L, first, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 4L, 4L, null, null, 0L));
            fixture.template.getMapper(StreamMetricBucketMapper.class).insert(
                    metric(502L, 602L, second, 2L, 2L, 0L, 0L, 20L, 1L, 0L, 3L, 3L, null, null, 0L));
            fixture.template.getMapper(StreamMetricBucketMapper.class).insert(
                    metric(503L, 603L, third, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 2L, 2L, null, null, 0L));

            PageView<StreamingMetricBucketView> all = fixture.service.metricsPage(task, null, null, 1, 10, false);
            PageView<StreamingMetricBucketView> recordsOnly = fixture.service.metricsPage(task, null, null, 1, 10, true);
            PageView<StreamingMetricBucketView> recordsOnlyPage = fixture.service.metricsPage(task, null, null, 1, 1, true);

            assertEquals(3L, all.getTotal());
            assertEquals(3, all.getItems().size());
            assertEquals(1L, recordsOnly.getTotal());
            assertEquals(1, recordsOnly.getItems().size());
            assertEquals(second, recordsOnly.getItems().get(0).getBucketStart());
            assertEquals(1L, recordsOnlyPage.getTotal());
            assertEquals(1, recordsOnlyPage.getItems().size());
        }
    }

    private StreamMetricBucketEntity metric(Long id,
                                             Long attemptId,
                                             LocalDateTime bucket,
                                             Long recordsRead,
                                             Long writeSucceedRecords,
                                             Long writeFailedRecords,
                                             Long dirtyRecords,
                                             Long bytesRead,
                                             Long batchCount,
                                             Long retryCount,
                                             Long currentLag,
                                             Long maxLag,
                                             LocalDateTime lastMessageAt,
                                             LocalDateTime lastCheckpointAt,
                                             Long rebalanceCount) {
        StreamMetricBucketEntity entity = new StreamMetricBucketEntity();
        entity.setId(id);
        entity.setTenantId("default");
        entity.setProjectId(20L);
        entity.setCollectionTaskId(100L);
        entity.setRunId(401L);
        entity.setAttemptId(attemptId);
        entity.setBucketStart(bucket);
        entity.setRecordsRead(recordsRead);
        entity.setWriteSucceedRecords(writeSucceedRecords);
        entity.setWriteFailedRecords(writeFailedRecords);
        entity.setDirtyRecords(dirtyRecords);
        entity.setBytesRead(bytesRead);
        entity.setBatchCount(batchCount);
        entity.setRetryCount(retryCount);
        entity.setCurrentLag(currentLag);
        entity.setMaxLag(maxLag);
        entity.setLastMessageAt(lastMessageAt);
        entity.setLastCheckpointAt(lastCheckpointAt);
        entity.setRebalanceCount(rebalanceCount);
        return entity;
    }

    private static void awaitAndOnline(CountDownLatch start,
                                       StreamingTaskRuntimeService service,
                                       CollectionTaskDefinitionEntity task) {
        try {
            start.await();
            service.online(task);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static void awaitAndOffline(CountDownLatch start,
                                        StreamingTaskRuntimeService service,
                                        CollectionTaskDefinitionEntity task) {
        try {
            start.await();
            service.offline(task);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private Fixture fixture() throws Exception {
        Path database = Files.createTempFile("studio-stream-runtime-", ".sqlite");
        String url = "jdbc:sqlite:" + database.toAbsolutePath() + "?busy_timeout=10000";
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            statement.execute("pragma journal_mode=WAL");
            statement.execute("create table collection_task_definition ("
                    + "id integer primary key, tenant_id text, project_id integer, deleted integer default 0,"
                    + "created_at text, updated_at text, created_by integer, runtime_cluster_id integer, name text,"
                    + "task_type text, status text, execution_mode text, source_count integer,"
                    + "target_datasource_name_snapshot text, target_datasource_type_code_snapshot text,"
                    + "target_model_name_snapshot text, target_model_physical_locator_snapshot text,"
                    + "source_bindings_json text, target_binding_json text, field_mappings_json text,"
                    + "execution_options_json text, streaming_options_json text)");
            statement.executeUpdate("insert into collection_task_definition "
                    + "(id, tenant_id, project_id, deleted, runtime_cluster_id, name, task_type, status, execution_mode, "
                    + "source_count, streaming_options_json) values "
                    + "(100, 'default', 20, 0, 30, 'NativeStreaming-M3', 'SINGLE_TABLE', 'DRAFT', "
                    + "'STREAMING', 1, '{\"groupId\":\"studio.default.100\"}')");
            JdbcTemplate jdbc = new JdbcTemplate(new UnpooledDataSource("org.sqlite.JDBC", url, null));
            StudioStreamingSchemaUpgradeSupport support = new StudioStreamingSchemaUpgradeSupport(
                    jdbc, new StudioSchemaIntrospector(jdbc));
            support.ensureSqlite();
        }

        UnpooledDataSource dataSource = new UnpooledDataSource("org.sqlite.JDBC", url, null);
        SqlSessionTemplate template = new SqlSessionTemplate(sessionFactory(dataSource));
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("default");
        when(security.currentUserId()).thenReturn(7L);
        StreamTaskDeployMapper deployMapper = template.getMapper(StreamTaskDeployMapper.class);
        StreamingTaskRuntimeService service = new StreamingTaskRuntimeService(
                deployMapper,
                template.getMapper(StreamTaskRunMapper.class),
                template.getMapper(StreamTaskAttemptMapper.class),
                template.getMapper(StreamMetricBucketMapper.class),
                template.getMapper(StreamTaskEventMapper.class),
                template.getMapper(RunLogChunkMapper.class),
                security);
        return new Fixture(database, new JdbcTemplate(dataSource), template,
                template.getMapper(CollectionTaskDefinitionMapper.class), deployMapper, service);
    }

    private SqlSessionFactory sessionFactory(UnpooledDataSource dataSource) {
        Environment environment = new Environment(
                "stream-runtime-test", new JdbcTransactionFactory(), dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig()
                .setDbConfig(new GlobalConfig.DbConfig())
                .setMetaObjectHandler(new MybatisPlusConfig().metaObjectHandler()));
        configuration.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor());
        configuration.addMapper(CollectionTaskDefinitionMapper.class);
        configuration.addMapper(StreamTaskDeployMapper.class);
        configuration.addMapper(StreamTaskRunMapper.class);
        configuration.addMapper(StreamTaskAttemptMapper.class);
        configuration.addMapper(StreamMetricBucketMapper.class);
        configuration.addMapper(StreamTaskEventMapper.class);
        configuration.addMapper(RunLogChunkMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private static final class Fixture implements AutoCloseable {
        private final Path database;
        private final JdbcTemplate jdbc;
        private final SqlSessionTemplate template;
        private final CollectionTaskDefinitionMapper definitionMapper;
        private final StreamTaskDeployMapper deployMapper;
        private final StreamingTaskRuntimeService service;

        private Fixture(Path database,
                        JdbcTemplate jdbc,
                        SqlSessionTemplate template,
                        CollectionTaskDefinitionMapper definitionMapper,
                        StreamTaskDeployMapper deployMapper,
                        StreamingTaskRuntimeService service) {
            this.database = database;
            this.jdbc = jdbc;
            this.template = template;
            this.definitionMapper = definitionMapper;
            this.deployMapper = deployMapper;
            this.service = service;
        }

        private CollectionTaskDefinitionEntity task() {
            CollectionTaskDefinitionEntity task = definitionMapper.selectById(100L);
            assertEquals(CollectionTaskExecutionMode.STREAMING.name(), task.getExecutionMode());
            CollectionTaskStreamingOptions options = new ObjectMapper().convertValue(
                    task.getStreamingOptionsJson(), CollectionTaskStreamingOptions.class);
            assertEquals("studio.default.100", options.getGroupId());
            return task;
        }

        private int count(String sql) {
            Integer value = jdbc.queryForObject(sql, Integer.class);
            return value == null ? 0 : value;
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
