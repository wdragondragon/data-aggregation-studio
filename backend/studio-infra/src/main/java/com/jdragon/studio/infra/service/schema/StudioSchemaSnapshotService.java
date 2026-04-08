package com.jdragon.studio.infra.service.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.model.schema.ColumnSchemaSnapshot;
import com.jdragon.studio.infra.model.schema.DatabaseSchemaSnapshot;
import com.jdragon.studio.infra.model.schema.IndexSchemaSnapshot;
import com.jdragon.studio.infra.model.schema.TableSchemaSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class StudioSchemaSnapshotService {

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public StudioSchemaSnapshotService(JdbcTemplate jdbcTemplate,
                                       DataSource dataSource,
                                       ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    public DatabaseSchemaSnapshot describeCurrentDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            DatabaseSchemaSnapshot snapshot = new DatabaseSchemaSnapshot();
            snapshot.setDatabaseName(currentDatabaseName());
            snapshot.setDatabaseProductName(metaData.getDatabaseProductName());
            snapshot.setDatabaseProductVersion(metaData.getDatabaseProductVersion());
            snapshot.setGeneratedAt(LocalDateTime.now());
            snapshot.setTables(loadTables());
            return snapshot;
        } catch (Exception e) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to describe current database schema: " + e.getMessage(), e);
        }
    }

    public Path writeSnapshot(Path outputDirectory) {
        DatabaseSchemaSnapshot snapshot = describeCurrentDatabase();
        try {
            recreateDirectory(outputDirectory);
            Path tablesDirectory = Files.createDirectories(outputDirectory.resolve("tables"));
            Path snapshotFile = outputDirectory.resolve("schema.json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(snapshotFile.toFile(), snapshot);
            for (TableSchemaSnapshot table : snapshot.getTables()) {
                String fileName = String.format("%03d-%s.sql", table.getOrdinal(), table.getTableName());
                Path ddlFile = tablesDirectory.resolve(fileName);
                Files.write(ddlFile,
                        table.getCreateSql().getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            }
            return snapshotFile;
        } catch (IOException e) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to write schema snapshot: " + e.getMessage(), e);
        }
    }

    public DatabaseSchemaSnapshot readSnapshot(Path inputDirectory) {
        Path snapshotFile = inputDirectory.resolve("schema.json");
        if (!Files.exists(snapshotFile)) {
            throw new StudioException(StudioErrorCode.NOT_FOUND,
                    "Schema snapshot file not found: " + snapshotFile.toAbsolutePath());
        }
        try {
            return objectMapper.readValue(snapshotFile.toFile(), DatabaseSchemaSnapshot.class);
        } catch (IOException e) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to read schema snapshot: " + e.getMessage(), e);
        }
    }

    private List<TableSchemaSnapshot> loadTables() {
        List<TableSchemaSnapshot> result = new ArrayList<TableSchemaSnapshot>();
        List<Map<String, Object>> tableRows = jdbcTemplate.queryForList(
                "select table_name, engine, table_collation, table_comment, create_time " +
                        "from information_schema.tables " +
                        "where table_schema = database() and table_type = 'BASE TABLE' " +
                        "order by create_time asc, table_name asc");
        int ordinal = 1;
        for (Map<String, Object> row : tableRows) {
            String tableName = stringValue(row.get("table_name"));
            TableSchemaSnapshot table = new TableSchemaSnapshot();
            table.setOrdinal(ordinal++);
            table.setTableName(tableName);
            table.setEngine(stringValue(row.get("engine")));
            table.setTableCollation(stringValue(row.get("table_collation")));
            table.setTableComment(stringValue(row.get("table_comment")));
            table.setCreateSql(loadCreateSql(tableName));
            table.setColumns(loadColumns(tableName));
            table.setIndexes(loadIndexes(tableName));
            result.add(table);
        }
        result.sort(Comparator.comparing(TableSchemaSnapshot::getOrdinal));
        return result;
    }

    private String loadCreateSql(String tableName) {
        return jdbcTemplate.query("show create table `" + tableName + "`", rs -> {
            if (!rs.next()) {
                throw new StudioException(StudioErrorCode.NOT_FOUND, "Table not found: " + tableName);
            }
            return rs.getString(2);
        });
    }

    private List<ColumnSchemaSnapshot> loadColumns(String tableName) {
        List<ColumnSchemaSnapshot> result = new ArrayList<ColumnSchemaSnapshot>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select ordinal_position, column_name, column_type, is_nullable, column_default, extra, column_key, column_comment " +
                        "from information_schema.columns " +
                        "where table_schema = database() and table_name = ? " +
                        "order by ordinal_position asc",
                tableName);
        for (Map<String, Object> row : rows) {
            ColumnSchemaSnapshot column = new ColumnSchemaSnapshot();
            column.setOrdinalPosition(integerValue(row.get("ordinal_position")));
            column.setColumnName(stringValue(row.get("column_name")));
            column.setColumnType(stringValue(row.get("column_type")));
            column.setNullable("YES".equalsIgnoreCase(stringValue(row.get("is_nullable"))));
            column.setDefaultValue(stringValue(row.get("column_default")));
            String extra = stringValue(row.get("extra"));
            column.setExtra(extra);
            column.setAutoIncrement(extra != null && extra.toLowerCase().contains("auto_increment"));
            column.setColumnKey(stringValue(row.get("column_key")));
            column.setColumnComment(stringValue(row.get("column_comment")));
            result.add(column);
        }
        return result;
    }

    private List<IndexSchemaSnapshot> loadIndexes(String tableName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select index_name, non_unique, seq_in_index, column_name, index_type " +
                        "from information_schema.statistics " +
                        "where table_schema = database() and table_name = ? " +
                        "order by case when index_name = 'PRIMARY' then 0 else 1 end, index_name asc, seq_in_index asc",
                tableName);
        Map<String, IndexSchemaSnapshot> indexes = new LinkedHashMap<String, IndexSchemaSnapshot>();
        Map<String, LinkedHashSet<String>> columnsByIndex = new LinkedHashMap<String, LinkedHashSet<String>>();
        for (Map<String, Object> row : rows) {
            String indexName = stringValue(row.get("index_name"));
            IndexSchemaSnapshot index = indexes.get(indexName);
            if (index == null) {
                index = new IndexSchemaSnapshot();
                index.setIndexName(indexName);
                index.setPrimary("PRIMARY".equalsIgnoreCase(indexName));
                index.setUnique(integerValue(row.get("non_unique")) == 0);
                index.setIndexType(stringValue(row.get("index_type")));
                indexes.put(indexName, index);
                columnsByIndex.put(indexName, new LinkedHashSet<String>());
            }
            String columnName = stringValue(row.get("column_name"));
            if (columnName != null) {
                columnsByIndex.get(indexName).add(columnName);
            }
        }
        List<IndexSchemaSnapshot> result = new ArrayList<IndexSchemaSnapshot>();
        for (Map.Entry<String, IndexSchemaSnapshot> entry : indexes.entrySet()) {
            entry.getValue().setColumns(new ArrayList<String>(columnsByIndex.get(entry.getKey())));
            result.add(entry.getValue());
        }
        return result;
    }

    private String currentDatabaseName() {
        return jdbcTemplate.queryForObject("select database()", String.class);
    }

    private void recreateDirectory(Path outputDirectory) throws IOException {
        if (Files.exists(outputDirectory)) {
            Files.walk(outputDirectory)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new IllegalStateException("Failed to clean output directory: " + path, e);
                        }
                    });
        }
        Files.createDirectories(outputDirectory);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return null;
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
