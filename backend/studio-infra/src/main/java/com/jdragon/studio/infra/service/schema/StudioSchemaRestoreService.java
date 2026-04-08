package com.jdragon.studio.infra.service.schema;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.model.schema.DatabaseSchemaSnapshot;
import com.jdragon.studio.infra.model.schema.TableSchemaSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class StudioSchemaRestoreService {

    private final JdbcTemplate jdbcTemplate;
    private final StudioSchemaSnapshotService schemaSnapshotService;

    public StudioSchemaRestoreService(JdbcTemplate jdbcTemplate,
                                      StudioSchemaSnapshotService schemaSnapshotService) {
        this.jdbcTemplate = jdbcTemplate;
        this.schemaSnapshotService = schemaSnapshotService;
    }

    public void restore(Path inputDirectory) {
        DatabaseSchemaSnapshot snapshot = schemaSnapshotService.readSnapshot(inputDirectory);
        if (snapshot.getTables() == null || snapshot.getTables().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Schema snapshot does not contain any tables: " + inputDirectory.toAbsolutePath());
        }
        List<String> existingTables = listExistingTables();
        jdbcTemplate.execute("set foreign_key_checks = 0");
        try {
            Collections.reverse(existingTables);
            for (String tableName : existingTables) {
                jdbcTemplate.execute("drop table if exists `" + tableName + "`");
            }
            for (TableSchemaSnapshot table : snapshot.getTables()) {
                jdbcTemplate.execute(table.getCreateSql());
            }
        } finally {
            jdbcTemplate.execute("set foreign_key_checks = 1");
        }
    }

    private List<String> listExistingTables() {
        List<String> rows = jdbcTemplate.query("show full tables where Table_type = 'BASE TABLE'",
                (rs, rowNum) -> rs.getString(1));
        return rows == null ? new ArrayList<String>() : new ArrayList<String>(rows);
    }
}
