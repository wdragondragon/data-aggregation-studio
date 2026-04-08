package com.jdragon.studio.infra.model.schema;

import lombok.Data;

@Data
public class ColumnSchemaSnapshot {
    private Integer ordinalPosition;
    private String columnName;
    private String columnType;
    private Boolean nullable;
    private String defaultValue;
    private Boolean autoIncrement;
    private String columnKey;
    private String extra;
    private String columnComment;
}
