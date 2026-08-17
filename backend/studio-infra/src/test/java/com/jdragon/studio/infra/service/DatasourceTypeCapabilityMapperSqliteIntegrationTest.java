package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.jdragon.studio.infra.mapper.DatasourceTypeCapabilityMapper;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class DatasourceTypeCapabilityMapperSqliteIntegrationTest {

    @Test
    void readsNestedRuntimeCapabilitiesFromHistoricalRows() throws Exception {
        Path database = Files.createTempFile("studio-datasource-capability-", ".sqlite");
        String url = "jdbc:sqlite:" + database.toAbsolutePath();
        try {
            createCapabilityTable(url);
            UnpooledDataSource dataSource = new UnpooledDataSource("org.sqlite.JDBC", url, null);
            SqlSessionFactory factory = sessionFactory(dataSource);
            try (SqlSession session = factory.openSession()) {
                DatasourceTypeCapabilityService service = new DatasourceTypeCapabilityService(
                        session.getMapper(DatasourceTypeCapabilityMapper.class),
                        new JdbcTemplate(dataSource));

                assertThat(service.typesWithRuntimeCapability("browse", true))
                        .containsExactly("local", "ftp", "file", "local_file");
                assertThat(service.hasRuntimeCapability("ftp", "browse")).isTrue();
                assertThat(service.hasRuntimeCapability("mysql8", "browse")).isFalse();
            }
        } finally {
            Files.deleteIfExists(database);
        }
    }

    private SqlSessionFactory sessionFactory(UnpooledDataSource dataSource) {
        Environment environment = new Environment(
                "datasource-capability-test", new JdbcTransactionFactory(), dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(DatasourceTypeCapabilityMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private void createCapabilityTable(String url) throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            statement.execute("create table datasource_type_capability (" +
                    "id integer primary key, tenant_id text, deleted integer default 0," +
                    "created_at text, updated_at text, type_code text, type_name text," +
                    "enabled integer, readable integer, writable integer, executable integer," +
                    "sql_executable integer, source_category text, source_plugin text," +
                    "reader_plugins_json text, writer_plugins_json text," +
                    "runtime_capabilities_json text, sort_order integer, description text)");
            statement.executeUpdate(row(1, "local", 10, binaryFileCapabilities()));
            statement.executeUpdate(row(2, "ftp", 20, binaryFileCapabilities()));
            statement.executeUpdate(row(3, "mysql8", 30, "{}"));
        }
    }

    private String row(long id, String typeCode, int sortOrder, String runtimeCapabilities) {
        return "insert into datasource_type_capability " +
                "(id, tenant_id, deleted, type_code, type_name, enabled, readable, writable, " +
                "executable, sql_executable, source_category, source_plugin, reader_plugins_json, " +
                "writer_plugins_json, runtime_capabilities_json, sort_order) values (" +
                id + ", 'default', 0, '" + typeCode + "', '" + typeCode + "', 1, 1, 1, " +
                "1, 0, 'FILE_SYSTEM', '" + typeCode + "', '[]', '[]', '" +
                runtimeCapabilities + "', " + sortOrder + ")";
    }

    private String binaryFileCapabilities() {
        return "{\"binaryFile\":{\"browse\":true,\"read\":true," +
                "\"write\":true,\"manage\":true," +
                "\"transferSource\":true,\"transferTarget\":true}}";
    }
}
