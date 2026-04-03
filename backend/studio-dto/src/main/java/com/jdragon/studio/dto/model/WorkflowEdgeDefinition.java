package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.EdgeCondition;
import lombok.Data;

@Data
public class WorkflowEdgeDefinition {
    private String fromNodeCode;
    private String toNodeCode;
    private EdgeCondition condition;
}

