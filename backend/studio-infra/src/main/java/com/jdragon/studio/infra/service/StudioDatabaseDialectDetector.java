package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Locale;

final class StudioDatabaseDialectDetector {

    private final JdbcTemplate jdbcTemplate;

    StudioDatabaseDialectDetector(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    StudioDatabaseDialect detect() {
        String databaseProduct = jdbcTemplate.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName());
        if (databaseProduct == null) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR, "Unable to detect database product");
        }
        String normalized = databaseProduct.toLowerCase(Locale.ENGLISH);
        if (normalized.contains("mysql")) {
            return StudioDatabaseDialect.MYSQL;
        }
        if (normalized.contains("sqlite")) {
            return StudioDatabaseDialect.SQLITE;
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unsupported database product for schema upgrade: " + databaseProduct);
    }
}
