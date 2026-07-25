package com.jdragon.studio.worker.runtime;

import com.jdragon.studio.core.spi.NodeExecutor;
import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.infra.service.QualityTaskExecutionService;
import com.jdragon.studio.infra.service.QualityTaskService;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class QualityTaskNodeExecutor implements NodeExecutor {

    private final QualityTaskService qualityTaskService;
    private final QualityTaskExecutionService qualityTaskExecutionService;

    public QualityTaskNodeExecutor(QualityTaskService qualityTaskService,
                                   QualityTaskExecutionService qualityTaskExecutionService) {
        this.qualityTaskService = qualityTaskService;
        this.qualityTaskExecutionService = qualityTaskExecutionService;
    }

    @Override
    public boolean supports(WorkflowNodeDefinition definition) {
        return definition != null && definition.getNodeType() == NodeType.QUALITY_TASK;
    }

    @Override
    public Map<String, Object> execute(WorkflowNodeDefinition definition, Map<String, Object> runtimeContext) {
        Long qualityTaskId = resolveQualityTaskId(definition, runtimeContext);
        return qualityTaskExecutionService.execute(qualityTaskService.requireOnlineForExecution(qualityTaskId));
    }

    private Long resolveQualityTaskId(WorkflowNodeDefinition definition, Map<String, Object> runtimeContext) {
        if (definition != null && definition.getConfig() != null) {
            Long qualityTaskId = parseLong(definition.getConfig().get("qualityTaskId"));
            if (qualityTaskId != null) {
                return qualityTaskId;
            }
        }
        if (runtimeContext != null) {
            Long qualityTaskId = parseLong(runtimeContext.get("qualityTaskId"));
            if (qualityTaskId != null) {
                return qualityTaskId;
            }
        }
        throw new IllegalStateException("qualityTaskId is required for QUALITY_TASK execution");
    }

    private Long parseLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String && !((String) value).trim().isEmpty()) {
            return Long.parseLong(((String) value).trim());
        }
        return null;
    }
}
