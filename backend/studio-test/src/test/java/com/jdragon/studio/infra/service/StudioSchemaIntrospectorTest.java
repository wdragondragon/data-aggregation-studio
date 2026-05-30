package com.jdragon.studio.infra.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioSchemaIntrospectorTest {

    @Test
    void ensureColumnShouldIgnoreDuplicateColumnWhenMetadataMissesExistingColumn() {
        DuplicateColumnJdbcTemplate jdbcTemplate = new DuplicateColumnJdbcTemplate();
        StudioSchemaIntrospector introspector = new StudioSchemaIntrospector(jdbcTemplate);

        assertDoesNotThrow(() -> introspector.ensureColumn("data_ingestion_service",
                "webservice_enabled",
                "alter table data_ingestion_service add column webservice_enabled int default 0"));
        assertTrue(jdbcTemplate.executed);
    }

    @Test
    void ensureColumnShouldRethrowNonDuplicateDdlError() {
        FailingJdbcTemplate jdbcTemplate = new FailingJdbcTemplate();
        StudioSchemaIntrospector introspector = new StudioSchemaIntrospector(jdbcTemplate);

        assertThrows(BadSqlGrammarException.class, () -> introspector.ensureColumn("data_ingestion_service",
                "webservice_enabled",
                "alter table data_ingestion_service add column webservice_enabled int default 0"));
    }

    private static class DuplicateColumnJdbcTemplate extends JdbcTemplate {
        private boolean executed;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(ConnectionCallback<T> action) {
            return (T) Boolean.FALSE;
        }

        @Override
        public void execute(String sql) throws DataAccessException {
            executed = true;
            throw new BadSqlGrammarException("alter", sql,
                    new SQLException("Duplicate column name 'webservice_enabled'", "42S21", 1060));
        }
    }

    private static final class FailingJdbcTemplate extends DuplicateColumnJdbcTemplate {
        @Override
        public void execute(String sql) throws DataAccessException {
            throw new BadSqlGrammarException("alter", sql,
                    new SQLException("Unknown column failure", "42000", 1105));
        }
    }
}
