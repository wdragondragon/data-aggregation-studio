package com.jdragon.studio.core.spi;

import com.jdragon.studio.dto.model.WorkflowNodeDefinition;

import java.util.Map;

public interface JobConfigAssembler {
    boolean supports(WorkflowNodeDefinition definition);

    Map<String, Object> assemble(WorkflowNodeDefinition definition, Map<String, Object> runtimeContext);
}

