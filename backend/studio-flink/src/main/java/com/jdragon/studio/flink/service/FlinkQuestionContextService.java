package com.jdragon.studio.flink.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.request.FlinkQuestionAskRequest;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataSourceService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
class FlinkQuestionContextService {
    private static final int MAX_CONTEXT_MODELS = 20;

    private final DataModelService dataModelService;
    private final DataSourceService dataSourceService;

    FlinkQuestionContextService(DataModelService dataModelService, DataSourceService dataSourceService) {
        this.dataModelService = dataModelService;
        this.dataSourceService = dataSourceService;
    }

    FlinkQuestionContext build(FlinkQuestionAskRequest request) {
        List<DataModelDefinition> candidates = resolveModels(request);
        if (candidates.isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "No readable data models are available for Flink question answering");
        }
        FlinkQuestionContext context = new FlinkQuestionContext();
        context.getModels().addAll(candidates);
        context.setPromptContext(buildPromptContext(candidates));
        return context;
    }

    private List<DataModelDefinition> resolveModels(FlinkQuestionAskRequest request) {
        Set<Long> seen = new LinkedHashSet<Long>();
        List<DataModelDefinition> result = new ArrayList<DataModelDefinition>();
        if (request.getModelIds() != null && !request.getModelIds().isEmpty()) {
            for (Long modelId : request.getModelIds()) {
                addModel(result, seen, dataModelService.get(modelId));
            }
            return result;
        }
        if (request.getDatasourceIds() != null && !request.getDatasourceIds().isEmpty()) {
            for (Long datasourceId : request.getDatasourceIds()) {
                for (DataModelDefinition model : dataModelService.listByDatasource(datasourceId)) {
                    addModel(result, seen, model);
                    if (result.size() >= MAX_CONTEXT_MODELS) {
                        return result;
                    }
                }
            }
            return result;
        }
        for (DataModelDefinition model : dataModelService.list()) {
            addModel(result, seen, model);
            if (result.size() >= MAX_CONTEXT_MODELS) {
                return result;
            }
        }
        return result;
    }

    private void addModel(List<DataModelDefinition> result, Set<Long> seen, DataModelDefinition model) {
        if (model != null && model.getId() != null && seen.add(model.getId())) {
            result.add(model);
        }
    }

    private String buildPromptContext(List<DataModelDefinition> models) {
        StringBuilder builder = new StringBuilder();
        builder.append("Available Flink SQL tables. Use only these table names.\n");
        for (DataModelDefinition model : models) {
            DataSourceDefinition datasource = dataSourceService.getInternal(model.getDatasourceId());
            builder.append("- table: ").append(FlinkSqlExecutionService.tableNameFor(model))
                    .append(", modelName: ").append(model.getName())
                    .append(", datasourceType: ").append(datasource.getTypeCode())
                    .append(", physicalLocator: ").append(model.getPhysicalLocator()).append('\n');
            Object columns = model.getTechnicalMetadata() == null ? null : model.getTechnicalMetadata().get("columns");
            if (columns instanceof List<?>) {
                for (Object column : (List<?>) columns) {
                    if (column instanceof Map<?, ?>) {
                        Map<?, ?> map = (Map<?, ?>) column;
                        builder.append("  - ").append(map.get("name"))
                                .append(" ").append(map.get("type"));
                        Object remarks = map.get("remarks");
                        if (remarks != null) {
                            builder.append(" # ").append(remarks);
                        }
                        builder.append('\n');
                    }
                }
            } else {
                builder.append("  - payload STRING\n");
            }
        }
        return builder.toString();
    }
}
