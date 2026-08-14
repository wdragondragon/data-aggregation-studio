package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.FileTransferEventOutboxMapper;
import com.jdragon.studio.infra.mapper.FileTransferMetricSampleMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunItemMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunMapper;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class FileTransferStateMutationTransactionTest {

    @Test
    void outboxInsertFailureRollsBackBusinessStatusAndSuccessfulMutationCommitsBoth() throws Exception {
        Path database = Files.createTempFile("studio-file-transfer-outbox-", ".sqlite");
        try {
            TestContext context = new TestContext(database);
            context.jdbc.execute("create table file_transfer_run (" +
                    "id integer primary key, tenant_id text not null, project_id integer not null," +
                    "deleted integer not null default 0, created_at text, updated_at text," +
                    "run_record_id integer, task_id integer, task_name_snapshot text, trigger_type text," +
                    "direction text, channel text, status text, runtime_cluster_id integer," +
                    "source_runtime_cluster_id integer, source_datasource_id integer," +
                    "target_runtime_cluster_id integer, target_datasource_id integer," +
                    "total_files integer, success_files integer, skipped_files integer, failed_files integer," +
                    "conflict_files integer, resumed_files integer, post_action_failed_files integer," +
                    "total_bytes integer, transferred_bytes integer, failed_bytes integer, resumed_bytes integer," +
                    "current_bytes_per_second integer, peak_bytes_per_second integer, active_files integer," +
                    "retry_count integer, message text, started_at text, ended_at text, resolved_spec_json text)");
            context.jdbc.execute("create table file_transfer_event_outbox (" +
                    "id integer primary key, tenant_id text not null, project_id integer not null," +
                    "event_type text not null, run_id integer not null, item_id integer, occurred_at text not null," +
                    "payload_version integer not null, payload_json text, deleted integer default 0," +
                    "created_at text, updated_at text)");
            context.jdbc.update("insert into file_transfer_run " +
                    "(id, tenant_id, project_id, status, deleted) values (100, 'tenant-a', 10, 'QUEUED', 0)");
            context.jdbc.execute("create trigger reject_file_transfer_outbox before insert on " +
                    "file_transfer_event_outbox begin select raise(abort, 'outbox unavailable'); end");

            assertThatThrownBy(() -> context.transaction.executeWithoutResult(ignored ->
                    context.service.updateRunAndEvent(100L, statusUpdate("RUNNING"), false, true)))
                    .hasMessageContaining("outbox unavailable");
            assertThat(context.status()).isEqualTo("QUEUED");
            assertThat(context.outboxCount()).isZero();

            context.jdbc.execute("drop trigger reject_file_transfer_outbox");
            context.transaction.executeWithoutResult(ignored ->
                    context.service.updateRunAndEvent(100L, statusUpdate("RUNNING"), false, true));
            assertThat(context.status()).isEqualTo("RUNNING");
            assertThat(context.outboxCount()).isEqualTo(1);
        } finally {
            Files.deleteIfExists(database);
        }
    }

    @Test
    void failedBusinessUpdateDoesNotWriteOutboxAndMutationMethodsDeclareTransactionalBoundary() throws Exception {
        FileTransferRunMapper runMapper = mock(FileTransferRunMapper.class);
        FileTransferRunItemMapper itemMapper = mock(FileTransferRunItemMapper.class);
        FileTransferMetricSampleMapper metricMapper = mock(FileTransferMetricSampleMapper.class);
        FileTransferOutboxWriter writer = mock(FileTransferOutboxWriter.class);
        FileTransferStateMutationService service = new FileTransferStateMutationService(
                runMapper, itemMapper, metricMapper, writer);
        assertThat(service.updateRunAndEvent(100L, statusUpdate("RUNNING"), false, true)).isZero();
        verifyNoInteractions(writer);
        assertThat(FileTransferStateMutationService.class
                .getMethod("updateRunAndEvent", Long.class, LambdaUpdateWrapper.class,
                        boolean.class, boolean.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    void recoveryDispatchAndQueuedRunRollBackTogetherWhenOutboxWriteFails() throws Exception {
        Path database = Files.createTempFile("studio-file-transfer-recovery-", ".sqlite");
        try {
            TestContext context = new TestContext(database);
            context.createTransferTables();
            context.createDispatchTable();
            context.configureDispatchMapper();
            context.jdbc.update("update file_transfer_run set status='FAILED' where id=100");
            context.jdbc.execute("create trigger reject_file_transfer_outbox before insert on "
                    + "file_transfer_event_outbox begin select raise(abort, 'outbox unavailable'); end");

            assertThatThrownBy(() -> context.transaction.executeWithoutResult(ignored ->
                    context.service.requeueRunAndDispatchAndEvent(100L, statusUpdate("QUEUED"),
                            context.recoveryDispatch())))
                    .hasMessageContaining("outbox unavailable");
            assertThat(context.status()).isEqualTo("FAILED");
            assertThat(context.dispatchCount()).isZero();
            assertThat(context.outboxCount()).isZero();

            context.jdbc.execute("drop trigger reject_file_transfer_outbox");
            context.transaction.executeWithoutResult(ignored ->
                    context.service.requeueRunAndDispatchAndEvent(100L, statusUpdate("QUEUED"),
                            context.recoveryDispatch()));
            assertThat(context.status()).isEqualTo("QUEUED");
            assertThat(context.dispatchCount()).isEqualTo(1);
            assertThat(context.outboxCount()).isEqualTo(1);
        } finally {
            Files.deleteIfExists(database);
        }
    }

    @Test
    void resumeRunDispatchAndOutboxCommitOrRollBackAsOneUnit() throws Exception {
        Path database = Files.createTempFile("studio-file-transfer-resume-", ".sqlite");
        try {
            TestContext context = new TestContext(database);
            context.createTransferTables();
            context.createDispatchTable();
            context.configureDispatchMapper();
            context.jdbc.update("update file_transfer_run set status='PAUSED' where id=100");
            context.jdbc.execute("create trigger reject_resume_dispatch before insert on dispatch_task "
                    + "begin select raise(abort, 'dispatch unavailable'); end");

            FileTransferRunEntity run = context.run();
            assertThatThrownBy(() -> context.transaction.executeWithoutResult(ignored ->
                    context.service.resumeRunAndEnsureDispatchAndEvent(run, context.recoveryDispatch())))
                    .hasMessageContaining("dispatch unavailable");
            assertThat(context.status()).isEqualTo("PAUSED");
            assertThat(context.dispatchCount()).isZero();
            assertThat(context.outboxCount()).isZero();

            context.jdbc.execute("drop trigger reject_resume_dispatch");
            String resumedStatus = context.transaction.execute(ignored ->
                    context.service.resumeRunAndEnsureDispatchAndEvent(context.run(), context.recoveryDispatch()));
            assertThat(resumedStatus).isEqualTo("QUEUED");
            assertThat(context.status()).isEqualTo("QUEUED");
            assertThat(context.dispatchCount()).isEqualTo(1);
            assertThat(context.outboxCount()).isEqualTo(1);
        } finally {
            Files.deleteIfExists(database);
        }
    }

    private static LambdaUpdateWrapper<FileTransferRunEntity> statusUpdate(String status) {
        return new LambdaUpdateWrapper<FileTransferRunEntity>()
                .set(FileTransferRunEntity::getStatus, status)
                .eq(FileTransferRunEntity::getId, 100L);
    }

    private static final class TestContext {
        private final JdbcTemplate jdbc;
        private final TransactionTemplate transaction;
        private final FileTransferStateMutationService service;
        private final SqlSessionTemplate template;

        private TestContext(Path database) {
            UnpooledDataSource dataSource = new UnpooledDataSource(
                    "org.sqlite.JDBC", "jdbc:sqlite:" + database.toAbsolutePath(), null);
            jdbc = new JdbcTemplate(dataSource);
            DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
            transaction = new TransactionTemplate(transactionManager);

            Environment environment = new Environment("file-transfer-outbox-transaction-test",
                    new SpringManagedTransactionFactory(), dataSource);
            MybatisConfiguration configuration = new MybatisConfiguration(environment);
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(FileTransferRunMapper.class);
            configuration.addMapper(FileTransferRunItemMapper.class);
            configuration.addMapper(FileTransferMetricSampleMapper.class);
            configuration.addMapper(FileTransferEventOutboxMapper.class);
            configuration.addMapper(DispatchTaskMapper.class);
            SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
            template = new SqlSessionTemplate(factory);
            FileTransferRunMapper runMapper = template.getMapper(FileTransferRunMapper.class);
            FileTransferEventOutboxMapper outboxMapper = template.getMapper(FileTransferEventOutboxMapper.class);
            FileTransferOutboxWriter writer = new FileTransferOutboxWriter(outboxMapper,
                    new StudioPlatformProperties(),
                    new StaticListableBeanFactory().getBeanProvider(
                            io.micrometer.core.instrument.MeterRegistry.class));
            service = new FileTransferStateMutationService(runMapper,
                    template.getMapper(FileTransferRunItemMapper.class),
                    template.getMapper(FileTransferMetricSampleMapper.class), writer);
        }

        private String status() {
            return jdbc.queryForObject("select status from file_transfer_run where id=100", String.class);
        }

        private int outboxCount() {
            return jdbc.queryForObject("select count(*) from file_transfer_event_outbox", Integer.class);
        }

        private void createDispatchTable() {
            jdbc.execute("create table dispatch_task (" +
                    "id integer primary key, tenant_id text not null, project_id integer not null," +
                    "deleted integer not null default 0, created_at text, updated_at text," +
                    "execution_type text, file_transfer_run_id integer, status text, target_cluster_id integer," +
                    "node_code text, attempts integer, max_retries integer, payload_json text)");
        }

        private void createTransferTables() {
            jdbc.execute("create table file_transfer_run (" +
                    "id integer primary key, tenant_id text not null, project_id integer not null," +
                    "deleted integer not null default 0, created_at text, updated_at text," +
                    "run_record_id integer, task_id integer, task_name_snapshot text, trigger_type text," +
                    "direction text, channel text, status text, runtime_cluster_id integer," +
                    "source_runtime_cluster_id integer, source_datasource_id integer," +
                    "target_runtime_cluster_id integer, target_datasource_id integer," +
                    "total_files integer, success_files integer, skipped_files integer, failed_files integer," +
                    "conflict_files integer, resumed_files integer, post_action_failed_files integer," +
                    "total_bytes integer, transferred_bytes integer, failed_bytes integer, resumed_bytes integer," +
                    "current_bytes_per_second integer, peak_bytes_per_second integer, active_files integer," +
                    "retry_count integer, message text, started_at text, ended_at text, resolved_spec_json text)");
            jdbc.execute("create table file_transfer_event_outbox (" +
                    "id integer primary key, tenant_id text not null, project_id integer not null," +
                    "event_type text not null, run_id integer not null, item_id integer, occurred_at text not null," +
                    "payload_version integer not null, payload_json text, deleted integer default 0," +
                    "created_at text, updated_at text)");
            jdbc.update("insert into file_transfer_run " +
                    "(id, tenant_id, project_id, status, deleted) values (100, 'tenant-a', 10, 'QUEUED', 0)");
        }

        private void configureDispatchMapper() {
            service.setDispatchTaskMapper(template.getMapper(DispatchTaskMapper.class));
        }

        private DispatchTaskEntity recoveryDispatch() {
            DispatchTaskEntity dispatch = new DispatchTaskEntity();
            dispatch.setId(200L);
            dispatch.setTenantId("tenant-a");
            dispatch.setProjectId(10L);
            dispatch.setExecutionType("FILE_TRANSFER");
            dispatch.setFileTransferRunId(100L);
            dispatch.setStatus("QUEUED");
            dispatch.setTargetClusterId(50L);
            dispatch.setNodeCode("file_transfer_run_100");
            dispatch.setAttempts(0);
            dispatch.setMaxRetries(3);
            dispatch.setDeleted(0);
            dispatch.setCreatedAt(java.time.LocalDateTime.now());
            dispatch.setUpdatedAt(java.time.LocalDateTime.now());
            return dispatch;
        }

        private int dispatchCount() {
            return jdbc.queryForObject("select count(*) from dispatch_task", Integer.class);
        }

        private FileTransferRunEntity run() {
            return template.getMapper(FileTransferRunMapper.class).selectById(100L);
        }
    }
}
