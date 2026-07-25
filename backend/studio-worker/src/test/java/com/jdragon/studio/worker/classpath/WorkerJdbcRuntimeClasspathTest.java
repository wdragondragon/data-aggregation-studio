package com.jdragon.studio.worker.classpath;

import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class WorkerJdbcRuntimeClasspathTest {

    @Test
    void calciteJdbcDatasourceCanInitializeFromWorkerRuntimeClasspath() throws Exception {
        DataSource dataSource = JdbcSchema.dataSource(
                "jdbc:mysql://127.0.0.1:3306/runtime_classpath_check",
                "com.mysql.cj.jdbc.Driver",
                "runtime-check",
                "runtime-check"
        );

        Class<?> dbcpDataSourceClass = Class.forName("org.apache.commons.dbcp2.BasicDataSource");
        assertSame(dbcpDataSourceClass, dataSource.getClass());
        assertEquals("org.apache.commons.dbcp2.BasicDataSource", dataSource.getClass().getName());
    }
}
