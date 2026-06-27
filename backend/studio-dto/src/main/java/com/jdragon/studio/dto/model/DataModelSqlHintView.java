package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DataModelSqlHintView {
    private Long id;
    private Long datasourceId;
    private String name;
    private String physicalLocator;
    private List<String> columns = new ArrayList<String>();
}
