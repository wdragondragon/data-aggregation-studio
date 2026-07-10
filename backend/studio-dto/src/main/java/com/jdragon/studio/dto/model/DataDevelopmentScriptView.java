package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.ScriptType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class DataDevelopmentScriptView extends BaseDefinition {
    private Long directoryId;
    private String fileName;
    private ScriptType scriptType;
    private Long datasourceId;
    private String datasourceName;
    private String datasourceTypeCode;
    private Long environmentId;
    private String environmentName;
    private String description;
    private String content;
    private Map<String, Object> executionConfig = new LinkedHashMap<String, Object>();
}
