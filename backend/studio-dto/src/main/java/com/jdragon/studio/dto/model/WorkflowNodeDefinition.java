package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.NodeType;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class WorkflowNodeDefinition {
    private String nodeCode;
    private String nodeName;
    private NodeType nodeType;
    private Map<String, Object> config = new LinkedHashMap<String, Object>();
    private List<FieldMappingDefinition> fieldMappings = new ArrayList<FieldMappingDefinition>();
}

