package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DataModelLineageNodeView {
    private String nodeId;
    private String visualType;
    private Boolean focus;
    private String title;
    private String subtitle;
    private Long datasourceId;
    private Long modelId;
    private String datasourceName;
    private String datasourceType;
    private String databaseName;
    private String physicalLocator;
    private String host;
    private String port;
    private String dailyIncrement;
    private String totalCount;
    private List<DataModelLineageNodeFieldView> fields = new ArrayList<DataModelLineageNodeFieldView>();
}
