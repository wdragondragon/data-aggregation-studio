package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.model.WorkflowRunOutcome;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertWorkflowOutcomeMapperSqliteIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldReturnTwentyCompletedWorkflowOutcomesRegardlessOfNodeCount() throws Exception {
        UnpooledDataSource dataSource = new UnpooledDataSource(
                "org.sqlite.JDBC", "jdbc:sqlite:" + tempDir.resolve("workflow-outcomes.db"), null);
        createFixtures(dataSource);
        Configuration configuration = new Configuration(new Environment(
                "sqlite", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(RunRecordMapper.class);
        SqlSessionFactory sessionFactory = new SqlSessionFactoryBuilder().build(configuration);

        try (SqlSession session = sessionFactory.openSession()) {
            List<WorkflowRunOutcome> outcomes = session.getMapper(RunRecordMapper.class)
                    .selectRecentWorkflowRunOutcomes("tenant-a", 10L, 30L, 20);

            assertEquals(20, outcomes.size());
            assertEquals(21L, outcomes.get(0).getWorkflowRunId());
            assertEquals(2L, outcomes.get(19).getWorkflowRunId());
            assertTrue(outcomes.stream().allMatch(item -> Integer.valueOf(1).equals(item.getFailed())));
        }
    }

    private void createFixtures(UnpooledDataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create table run_record (id integer primary key, tenant_id text, project_id integer, " +
                    "deleted integer, workflow_definition_id integer, workflow_run_id integer, status text, " +
                    "ended_at timestamp, updated_at timestamp, created_at timestamp)");
            statement.execute("create table dispatch_task (id integer primary key, tenant_id text, project_id integer, " +
                    "deleted integer, workflow_run_id integer, status text)");
        }
        String insertRun = "insert into run_record " +
                "(id, tenant_id, project_id, deleted, workflow_definition_id, workflow_run_id, status, ended_at, updated_at, created_at) " +
                "values (?, 'tenant-a', 10, 0, 30, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(insertRun)) {
            long id = 1L;
            LocalDateTime base = LocalDateTime.of(2026, 7, 14, 0, 0);
            for (long workflowRunId = 1L; workflowRunId <= 22L; workflowRunId++) {
                for (int node = 0; node < 15; node++) {
                    LocalDateTime observedAt = base.plusMinutes(workflowRunId).plusSeconds(node);
                    statement.setLong(1, id++);
                    statement.setLong(2, workflowRunId);
                    statement.setString(3, workflowRunId == 1L ? "SUCCESS" : "FAILED");
                    statement.setTimestamp(4, Timestamp.valueOf(observedAt));
                    statement.setTimestamp(5, Timestamp.valueOf(observedAt));
                    statement.setTimestamp(6, Timestamp.valueOf(observedAt));
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("insert into dispatch_task values (1, 'tenant-a', 10, 0, 22, 'QUEUED')");
            statement.executeUpdate("insert into run_record values (10000, 'tenant-b', 10, 0, 30, 99, 'FAILED', " +
                    "'2026-07-15 00:00:00', '2026-07-15 00:00:00', '2026-07-15 00:00:00')");
        }
    }
}
