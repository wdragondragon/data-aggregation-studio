package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.ScriptType;
import lombok.Data;

@Data
public class DataDevelopmentScriptView extends BaseDefinition {
    private Long directoryId;
    private String fileName;
    private ScriptType scriptType;
    private Long datasourceId;
    private String datasourceName;
    private String datasourceTypeCode;
    private String description;
    private String content;
}
