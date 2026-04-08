package com.jdragon.studio.infra.model.schema;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class IndexSchemaSnapshot {
    private String indexName;
    private Boolean primary;
    private Boolean unique;
    private String indexType;
    private List<String> columns = new ArrayList<String>();
}
