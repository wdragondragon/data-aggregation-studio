package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.DataIngestionPayloadMode;
import com.jdragon.studio.dto.enums.DataIngestionSourcePosition;
import com.jdragon.studio.dto.enums.DataIngestionTargetType;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class DataIngestionSourceBinding {
    private String sourceCode;
    private String sourceName;
    private DataIngestionSourcePosition sourcePosition;
    private String sourcePath;
    private DataIngestionPayloadMode payloadMode;
    private DataIngestionTargetType targetType;
    private Long datasourceId;
    private String datasourceName;
    private String datasourceTypeCode;
    private Long modelId;
    private String modelName;
    private String modelPhysicalLocator;
    private Map<String, Object> writerOptions = new LinkedHashMap<String, Object>();
    private List<DataIngestionFieldMapping> fieldMappings = new ArrayList<DataIngestionFieldMapping>();
    private Integer sortOrder;
    private Boolean enabled;
}
