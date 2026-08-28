package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.CollectionTaskExecutionMode;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.infra.config.MybatisPlusConfig;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunTerminationSqliteIntegrationTest {

    @Test
    void streamingTaskMustUseOfflineInsteadOfBatchTermination() {
        CollectionTaskService collectionTaskService = mock(CollectionTaskService.class);
        CollectionTaskDefinitionView definition = new CollectionTaskDefinitionView();
        definition.setId(10L);
        definition.setExecutionMode(CollectionTaskExecutionMode.STREAMING);
        when(collectionTaskService.get(10L)).thenReturn(definition);
        DispatchTaskMapper dispatchMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        RunTerminationService service = new RunTerminationService(
                dispatchMapper, runRecordMapper, mock(RunService.class),
                collectionTaskService, mock(StudioSecurityService.class));

        StudioException exception = assertThrows(StudioException.class,
                () -> service.terminateCollectionTask(10L));

        assertTrue(exception.getMessage().contains("offline"));
        verifyNoInteractions(dispatchMapper, runRecordMapper);
    }

    @Test
    void persistsTerminationMetadataInDispatchAndRunRecordJson() throws Exception {
        Path database = Files.createTempFile("studio-run-termination-", ".sqlite");
        String url = "jdbc:sqlite:" + database.toAbsolutePath();
        try {
            createTables(url);
            SqlSessionFactory factory = sessionFactory(url);
            CollectionTaskService collectionTaskService = mock(CollectionTaskService.class);
            StudioSecurityService securityService = mock(StudioSecurityService.class);
            CollectionTaskDefinitionView definition = new CollectionTaskDefinitionView();
            definition.setId(10L);
            definition.setTenantId("tenant-a");
            definition.setProjectId(100L);
            when(collectionTaskService.get(10L)).thenReturn(definition);
            when(securityService.currentTenantId()).thenReturn("tenant-a");
            when(securityService.currentProjectId()).thenReturn(100L);
            when(securityService.currentUserId()).thenReturn(7L);
            when(securityService.currentUsername()).thenReturn("operator");

            try (SqlSession session = factory.openSession(true)) {
                RunTerminationService service = new RunTerminationService(
                        session.getMapper(DispatchTaskMapper.class),
                        session.getMapper(RunRecordMapper.class),
                        mock(RunService.class),
                        collectionTaskService,
                        securityService);

                assertEquals("FAILED", service.terminateCollectionTask(10L).getStatus());
            }

            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                ResultSet dispatch = statement.executeQuery(
                        "select status, termination_requested, payload_json from dispatch_task where id = 1");
                assertTrue(dispatch.next());
                assertEquals("FAILED", dispatch.getString("status"));
                assertEquals(1, dispatch.getInt("termination_requested"));
                assertTerminationJson(dispatch.getString("payload_json"));

                ResultSet record = statement.executeQuery(
                        "select status, termination_requested, payload_json, result_json from run_record where id = 20");
                assertTrue(record.next());
                assertEquals("FAILED", record.getString("status"));
                assertEquals(1, record.getInt("termination_requested"));
                assertTerminationJson(record.getString("payload_json"));
                assertTerminationJson(record.getString("result_json"));
            }
        } finally {
            Files.deleteIfExists(database);
            StudioRequestContextHolder.clear();
        }
    }

    private SqlSessionFactory sessionFactory(String url) {
        UnpooledDataSource dataSource = new UnpooledDataSource("org.sqlite.JDBC", url, null);
        Environment environment = new Environment("run-termination-test", new JdbcTransactionFactory(), dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(DispatchTaskMapper.class);
        configuration.addMapper(RunRecordMapper.class);
        MybatisPlusInterceptor interceptor = new MybatisPlusConfig().mybatisPlusInterceptor();
        configuration.addInterceptor(interceptor);
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private void createTables(String url) throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            statement.execute("create table dispatch_task (" +
                    "id integer primary key, tenant_id text, project_id integer, deleted integer default 0," +
                    "created_at text, updated_at text, execution_type text, workflow_run_id integer," +
                    "workflow_definition_id integer, workflow_version_id integer, collection_task_id integer," +
                    "quality_task_id integer, file_transfer_task_id integer, file_transfer_run_id integer," +
                    "triggered_by_user_id integer, run_record_id integer, node_code text, status text," +
                    "termination_requested integer default 0, target_cluster_id integer, resource_revision text," +
                    "claim_token text, worker_boot_id text, worker_group_code text, lease_owner text," +
                    "worker_instance_id text, lease_expires_at text, scheduled_fire_time text, attempts integer," +
                    "max_retries integer, protected_payload_ciphertext text, payload_json text)");
            statement.execute("create table run_record (" +
                    "id integer primary key, tenant_id text, project_id integer, deleted integer default 0," +
                    "created_at text, updated_at text, execution_type text, workflow_run_id integer," +
                    "workflow_definition_id integer, workflow_version_id integer, collection_task_id integer," +
                    "quality_task_id integer, file_transfer_task_id integer, file_transfer_run_id integer," +
                    "triggered_by_user_id integer, node_code text, status text, termination_requested integer default 0," +
                    "requested_cluster_id integer, actual_cluster_id integer, actual_cluster_code text," +
                    "worker_group_code text, worker_code text, worker_instance_id text, worker_boot_id text," +
                    "worker_pod_name text, worker_node_name text, message text, started_at text, ended_at text," +
                    "collected_records integer, read_succeed_records integer, read_failed_records integer," +
                    "write_succeed_records integer, write_failed_records integer, failed_records integer," +
                    "success_records integer, transformer_total_records integer, transformer_success_records integer," +
                    "transformer_failed_records integer, transformer_filter_records integer, log_file_path text," +
                    "log_size_bytes integer, log_charset text, log_storage_type text, log_object_bucket text," +
                    "log_object_key text, log_chunk_count integer, log_status text, log_error_summary text," +
                    "payload_json text, result_json text)");
            statement.executeUpdate("insert into dispatch_task (id, tenant_id, project_id, collection_task_id, " +
                    "execution_type, run_record_id, status, termination_requested, payload_json) values " +
                    "(1, 'tenant-a', 100, 10, 'COLLECTION_TASK', 20, 'RUNNING', 0, '{}')");
            statement.executeUpdate("insert into run_record (id, tenant_id, project_id, collection_task_id, " +
                    "status, termination_requested, message, payload_json, result_json) values " +
                    "(20, 'tenant-a', 100, 10, 'RUNNING', 0, 'running', '{}', '{}')");
        }
    }

    private void assertTerminationJson(String json) {
        assertTrue(json != null && json.contains("USER_TERMINATED"), json);
        assertTrue(json.contains("Manually terminated by user"), json);
        assertTrue(json.contains("terminationRequestedAt"), json);
        assertTrue(json.contains("terminationRequestedBy"), json);
    }
}
