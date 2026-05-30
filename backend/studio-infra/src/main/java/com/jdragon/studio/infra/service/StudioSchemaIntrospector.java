package com.jdragon.studio.infra.service;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

final class StudioSchemaIntrospector {

    private final JdbcTemplate jdbcTemplate;

    StudioSchemaIntrospector(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void ensureColumn(String tableName, String columnName, String ddl) {
        if (columnExists(tableName, columnName)) {
            return;
        }
        try {
            jdbcTemplate.execute(ddl);
        } catch (RuntimeException ex) {
            if (isDuplicateColumnError(ex) || columnExists(tableName, columnName)) {
                return;
            }
            throw ex;
        }
    }

    boolean tableExists(String tableName) {
        return Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            DatabaseMetaData metaData = connection.getMetaData();
            ResultSet tables = metaData.getTables(connection.getCatalog(), null, tableName, new String[]{"TABLE"});
            try {
                if (tables.next()) {
                    return true;
                }
            } finally {
                tables.close();
            }
            tables = metaData.getTables(connection.getCatalog(), null, tableName.toUpperCase(Locale.ENGLISH), new String[]{"TABLE"});
            try {
                return tables.next();
            } finally {
                tables.close();
            }
        }));
    }

    boolean columnExists(String tableName, String columnName) {
        return Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            DatabaseMetaData metaData = connection.getMetaData();
            ResultSet columns = metaData.getColumns(connection.getCatalog(), null, tableName, columnName);
            try {
                if (columns.next()) {
                    return true;
                }
            } finally {
                columns.close();
            }
            columns = metaData.getColumns(connection.getCatalog(), null,
                    tableName.toUpperCase(Locale.ENGLISH), columnName.toUpperCase(Locale.ENGLISH));
            try {
                return columns.next();
            } finally {
                columns.close();
            }
        }));
    }

    void ensureIndex(String tableName, String indexName, String ddl) {
        if (!indexExists(tableName, indexName)) {
            jdbcTemplate.execute(ddl);
        }
    }

    boolean indexMatchesColumns(String tableName, String indexName, String... expectedColumns) {
        return Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            DatabaseMetaData metaData = connection.getMetaData();
            ResultSet indexes = metaData.getIndexInfo(connection.getCatalog(), null, tableName, false, false);
            try {
                return indexMatchesColumns(indexes, indexName, expectedColumns);
            } finally {
                indexes.close();
            }
        })) || Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            DatabaseMetaData metaData = connection.getMetaData();
            ResultSet indexes = metaData.getIndexInfo(connection.getCatalog(), null,
                    tableName.toUpperCase(Locale.ENGLISH), false, false);
            try {
                return indexMatchesColumns(indexes, indexName, expectedColumns);
            } finally {
                indexes.close();
            }
        }));
    }

    boolean indexExists(String tableName, String indexName) {
        return Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            DatabaseMetaData metaData = connection.getMetaData();
            ResultSet indexes = metaData.getIndexInfo(connection.getCatalog(), null, tableName, false, false);
            try {
                while (indexes.next()) {
                    String current = indexes.getString("INDEX_NAME");
                    if (indexName.equalsIgnoreCase(current)) {
                        return true;
                    }
                }
            } finally {
                indexes.close();
            }
            indexes = metaData.getIndexInfo(connection.getCatalog(), null, tableName.toUpperCase(Locale.ENGLISH), false, false);
            try {
                while (indexes.next()) {
                    String current = indexes.getString("INDEX_NAME");
                    if (indexName.equalsIgnoreCase(current)) {
                        return true;
                    }
                }
            } finally {
                indexes.close();
            }
            return false;
        }));
    }

    private boolean indexMatchesColumns(ResultSet indexes, String indexName, String... expectedColumns) throws SQLException {
        Map<Short, String> actualColumns = new TreeMap<Short, String>();
        while (indexes.next()) {
            String current = indexes.getString("INDEX_NAME");
            if (!indexName.equalsIgnoreCase(current)) {
                continue;
            }
            String columnName = indexes.getString("COLUMN_NAME");
            short ordinal = indexes.getShort("ORDINAL_POSITION");
            if (columnName != null && ordinal > 0) {
                actualColumns.put(Short.valueOf(ordinal), columnName.toLowerCase(Locale.ENGLISH));
            }
        }
        if (actualColumns.size() != expectedColumns.length) {
            return false;
        }
        int position = 0;
        for (String actual : actualColumns.values()) {
            if (!expectedColumns[position].equalsIgnoreCase(actual)) {
                return false;
            }
            position++;
        }
        return true;
    }

    private boolean isDuplicateColumnError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException) {
                SQLException sqlException = (SQLException) current;
                if (sqlException.getErrorCode() == 1060 || "42S21".equalsIgnoreCase(sqlException.getSQLState())) {
                    return true;
                }
            }
            String message = current.getMessage();
            if (message != null) {
                String lowerMessage = message.toLowerCase(Locale.ENGLISH);
                if (lowerMessage.contains("duplicate column")
                        || lowerMessage.contains("duplicate column name")
                        || lowerMessage.contains("column already exists")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
