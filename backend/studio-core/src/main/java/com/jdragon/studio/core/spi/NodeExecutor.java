package com.jdragon.studio.core.spi;

import com.jdragon.studio.dto.model.WorkflowNodeDefinition;

import java.util.Map;

public interface NodeExecutor {
    boolean supports(WorkflowNodeDefinition definition);

    Map<String, Object> execute(WorkflowNodeDefinition definition, Map<String, Object> runtimeContext);
}

