package com.jdragon.studio.infra.model.schema;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class DatabaseSchemaSnapshot {
    private String databaseName;
    private String databaseProductName;
    private String databaseProductVersion;
    private LocalDateTime generatedAt;
    private List<TableSchemaSnapshot> tables = new ArrayList<TableSchemaSnapshot>();
}
