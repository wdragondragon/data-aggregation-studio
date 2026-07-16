package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.mapper.AlertDeliveryMapper;
import com.jdragon.studio.infra.mapper.AlertIncidentMapper;
import com.jdragon.studio.infra.mapper.AlertRuleMapper;
import com.jdragon.studio.infra.model.AlertProjectSummaryAggregate;
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
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AlertSummaryMapperSqliteIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldExecuteGroupedSummaryQueriesAgainstSqlite() throws Exception {
        UnpooledDataSource dataSource = new UnpooledDataSource(
                "org.sqlite.JDBC", "jdbc:sqlite:" + tempDir.resolve("alert-summary.db"), null);
        createSchemaAndFixtures(dataSource);

        Configuration configuration = new Configuration(new Environment(
                "sqlite", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AlertRuleMapper.class);
        configuration.addMapper(AlertIncidentMapper.class);
        configuration.addMapper(AlertDeliveryMapper.class);
        SqlSessionFactory sessionFactory = new SqlSessionFactoryBuilder().build(configuration);

        try (SqlSession session = sessionFactory.openSession()) {
            Map<Long, AlertProjectSummaryAggregate> rules = byProject(
                    session.getMapper(AlertRuleMapper.class)
                            .selectEnabledCountsByProjectIds("tenant-a", List.of(10L, 20L)));
            Map<Long, AlertProjectSummaryAggregate> incidents = byProject(
                    session.getMapper(AlertIncidentMapper.class)
                            .selectIncidentCountsByProjectIds("tenant-a", List.of(10L, 20L)));
            Map<Long, AlertProjectSummaryAggregate> deliveries = byProject(
                    session.getMapper(AlertDeliveryMapper.class)
                            .selectFailedCountsByProjectIds("tenant-a", List.of(10L, 20L)));

            assertEquals(2L, rules.get(10L).getEnabledRuleCount());
            assertEquals(1L, rules.get(20L).getEnabledRuleCount());
            assertEquals(1L, incidents.get(10L).getOpenIncidentCount());
            assertEquals(1L, incidents.get(10L).getCriticalIncidentCount());
            assertEquals(1L, incidents.get(20L).getOpenIncidentCount());
            assertEquals(0L, incidents.get(20L).getCriticalIncidentCount());
            assertEquals(2L, deliveries.get(10L).getFailedDeliveryCount());
            assertEquals(1L, deliveries.get(20L).getFailedDeliveryCount());
        }
    }

    private void createSchemaAndFixtures(UnpooledDataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create table studio_alert_rule (" +
                    "id integer primary key, tenant_id text, project_id integer, deleted integer, enabled integer)");
            statement.execute("create table studio_alert_incident (" +
                    "id integer primary key, tenant_id text, project_id integer, deleted integer, " +
                    "status text, severity text)");
            statement.execute("create table studio_alert_delivery (" +
                    "id integer primary key, tenant_id text, project_id integer, deleted integer, status text)");

            statement.executeUpdate("insert into studio_alert_rule values " +
                    "(1, 'tenant-a', 10, 0, 1), (2, 'tenant-a', 10, 0, 1), " +
                    "(3, 'tenant-a', 10, 0, 0), (4, 'tenant-a', 20, 0, 1), " +
                    "(5, 'tenant-a', 20, 1, 1), (6, 'tenant-b', 10, 0, 1)");
            statement.executeUpdate("insert into studio_alert_incident values " +
                    "(1, 'tenant-a', 10, 0, 'OPEN', 'CRITICAL'), " +
                    "(2, 'tenant-a', 10, 0, 'ACKNOWLEDGED', 'WARNING'), " +
                    "(3, 'tenant-a', 10, 0, 'RECOVERED', 'CRITICAL'), " +
                    "(4, 'tenant-a', 20, 0, 'OPEN', 'WARNING'), " +
                    "(5, 'tenant-a', 20, 1, 'OPEN', 'CRITICAL'), " +
                    "(6, 'tenant-b', 10, 0, 'OPEN', 'CRITICAL')");
            statement.executeUpdate("insert into studio_alert_delivery values " +
                    "(1, 'tenant-a', 10, 0, 'RETRY'), (2, 'tenant-a', 10, 0, 'DEAD'), " +
                    "(3, 'tenant-a', 10, 0, 'PENDING'), (4, 'tenant-a', 20, 0, 'RETRY'), " +
                    "(5, 'tenant-a', 20, 1, 'DEAD'), (6, 'tenant-b', 10, 0, 'DEAD')");
        }
    }

    private Map<Long, AlertProjectSummaryAggregate> byProject(List<AlertProjectSummaryAggregate> rows) {
        return rows.stream().collect(Collectors.toMap(AlertProjectSummaryAggregate::getProjectId, Function.identity()));
    }
}
