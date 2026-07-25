package com.jdragon.studio.infra.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.jdragon.studio.infra.entity.AlertIncidentEntity;
import com.jdragon.studio.infra.mapper.AlertIncidentMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AlertOptimisticLockIntegrationTest {

    @Test
    void shouldRejectAnUpdateUsingAStaleIncidentVersion() throws Exception {
        Path database = Files.createTempFile("studio-alert-version-", ".sqlite");
        String url = "jdbc:sqlite:" + database.toAbsolutePath();
        try {
            createIncidentTable(url);
            SqlSessionFactory factory = sessionFactory(url);
            AlertIncidentEntity stale;
            try (SqlSession session = factory.openSession()) {
                stale = session.getMapper(AlertIncidentMapper.class).selectById(1L);
            }
            try (SqlSession session = factory.openSession()) {
                AlertIncidentEntity current = session.getMapper(AlertIncidentMapper.class).selectById(1L);
                current.setStatus("ACKNOWLEDGED");
                assertEquals(1, session.getMapper(AlertIncidentMapper.class).updateById(current));
                session.commit();
            }
            stale.setStatus("CLOSED");
            try (SqlSession session = factory.openSession()) {
                assertEquals(0, session.getMapper(AlertIncidentMapper.class).updateById(stale));
                session.rollback();
            }
        } finally {
            Files.deleteIfExists(database);
        }
    }

    private SqlSessionFactory sessionFactory(String url) {
        UnpooledDataSource dataSource = new UnpooledDataSource("org.sqlite.JDBC", url, null);
        Environment environment = new Environment("alert-version-test", new JdbcTransactionFactory(), dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AlertIncidentMapper.class);
        configuration.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor());
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private void createIncidentTable(String url) throws Exception {
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.execute("create table studio_alert_incident (" +
                    "id integer primary key, tenant_id text, project_id integer, deleted integer default 0," +
                    "created_at text, updated_at text, rule_id integer, rule_name_snapshot text, rule_type text," +
                    "signature text, subject_type text, subject_key text, subject_id integer, subject_name_snapshot text," +
                    "target_path text, severity text, status text, summary text, requested_cluster_id integer," +
                    "actual_cluster_id integer, current_evidence_json text," +
                    "occurrence_count integer, notification_count integer, reopen_count integer, condition_active integer," +
                    "closed_while_active integer, first_triggered_at text, last_triggered_at text, last_notified_at text," +
                    "acknowledged_at text, recovered_at text, closed_at text, acknowledged_by integer, closed_by integer," +
                    "version integer default 0)");
            statement.executeUpdate("insert into studio_alert_incident " +
                    "(id, tenant_id, project_id, deleted, rule_id, signature, status, version) " +
                    "values (1, 'default', 20, 0, 10, 'sig', 'OPEN', 0)");
        }
    }
}
