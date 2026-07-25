package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.ScriptType;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class DataDevelopmentScriptListView extends BaseDefinition {
    private Long runtimeClusterId;
    private String runtimeClusterName;
    private Long directoryId;
    private String fileName;
    private ScriptType scriptType;
    private Long datasourceId;
    private String datasourceName;
    private String datasourceTypeCode;
    private Long environmentId;
    private String environmentName;
    private String description;
}
