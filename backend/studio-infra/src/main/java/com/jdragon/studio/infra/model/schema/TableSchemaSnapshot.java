package com.jdragon.studio.infra.model.schema;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TableSchemaSnapshot {
    private Integer ordinal;
    private String tableName;
    private String engine;
    private String tableCollation;
    private String tableComment;
    private String createSql;
    private List<ColumnSchemaSnapshot> columns = new ArrayList<ColumnSchemaSnapshot>();
    private List<IndexSchemaSnapshot> indexes = new ArrayList<IndexSchemaSnapshot>();
}
