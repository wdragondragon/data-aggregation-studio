package com.jdragon.studio.infra.service.execution;

import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.core.spi.JobConfigAssembler;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AggregationJobConfigAssembler implements JobConfigAssembler {

    @Override
    public boolean supports(WorkflowNodeDefinition definition) {
        return definition != null
                && (definition.getNodeType() == NodeType.ETL_SINGLE
                || definition.getNodeType() == NodeType.FUSION
                || definition.getNodeType() == NodeType.CONSISTENCY);
    }

    @Override
    public Map<String, Object> assemble(WorkflowNodeDefinition definition, Map<String, Object> runtimeContext) {
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("reader", definition.getConfig().get("reader"));
        config.put("writer", definition.getConfig().get("writer"));
        if (definition.getNodeType() == NodeType.ETL_SINGLE) {
            config.put("transformer", definition.getConfig().get("transformer"));
        }
        return config;
    }
}

