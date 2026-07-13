package com.jdragon.studio.flink.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.request.FlinkQuestionAskRequest;
import com.jdragon.studio.flink.connector.FilePathPushdownConfig;
import com.jdragon.studio.flink.connector.HttpPushdownMappingConfig;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataSourceService;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
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
                    .append(", physicalLocator: ")
                    .append(promptPhysicalLocator(model, datasource)).append('\n');
            Object columns = model.getTechnicalMetadata() == null ? null : model.getTechnicalMetadata().get("columns");
            if (columns instanceof List<?>) {
                builder.append("  row fields from source records:\n");
                for (Object column : (List<?>) columns) {
                    if (column instanceof Map<?, ?>) {
                        Map<?, ?> map = (Map<?, ?>) column;
                        builder.append("    - ").append(map.get("name"))
                                .append(" ").append(map.get("type"));
                        Object remarks = map.get("remarks");
                        if (remarks != null) {
                            builder.append(" # ").append(remarks);
                        }
                        builder.append('\n');
                    }
                }
            } else {
                builder.append("  row fields from source records:\n");
                builder.append("    - payload STRING\n");
            }
            FilePathPushdownConfig pathConfig = FilePathPushdownConfig.from(model.getTechnicalMetadata());
            if (pathConfig.isEnabled() && !pathConfig.getContexts().isEmpty()) {
                builder.append("  virtual path context fields, used only for file path pruning and not present in file rows:\n");
                for (FilePathPushdownConfig.Context context : pathConfig.getContexts()) {
                    builder.append("    - ").append(context.getField())
                            .append(" ").append(context.getType())
                            .append(" # ").append(context.getDisplayName())
                            .append("; aliases=").append(context.getAliases())
                            .append("; use this field for path/file-directory time filters\n");
                }
            }
            if ("http".equalsIgnoreCase(datasource.getTypeCode())) {
                builder.append("  HTTP pushdown convention, no extra model-page pushdown mapping is required:\n");
                builder.append("    - param.<field> or query.<field> writes an HTTP query parameter named <field>\n");
                builder.append("    - header.<field> writes an HTTP header, body.<field> writes a JSON body path, path.<field> writes a URL path variable\n");
                builder.append("    - unprefixed field is pushed only when it uniquely matches model reader params/header/body/path keys\n");
                builder.append("    - once a predicate is pushed, do not also keep it as a residual row filter; unmapped predicates remain normal row filters\n");
                HttpPushdownMappingConfig httpConfig = HttpPushdownMappingConfig.from(
                        model.getTechnicalMetadata(), model.getPhysicalLocator());
                for (HttpPushdownMappingConfig.Mapping mapping : httpConfig.getMappings()) {
                    builder.append("    - ")
                            .append(mapping.getLocation()).append('.').append(mapping.getField())
                            .append(" operators=").append(mapping.getSupportedOperators())
                            .append("; inferred from model reader options\n");
                }
            }
        }
        return builder.toString();
    }

    private String promptPhysicalLocator(DataModelDefinition model, DataSourceDefinition datasource) {
        String physicalLocator = model.getPhysicalLocator();
        if (!"http".equalsIgnoreCase(datasource.getTypeCode()) || physicalLocator == null) {
            return physicalLocator;
        }
        String trimmed = physicalLocator.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        try {
            String encoded = trimmed.replace("{", "%7B").replace("}", "%7D");
            URI uri = new URI(encoded);
            if (("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null) {
                URI sanitized = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                        uri.getPath(), null, null);
                return sanitized.toASCIIString().replace("%7B", "{").replace("%7D", "}");
            }
        } catch (URISyntaxException parseFailure) {
            // Fall through to conservative string sanitization for legacy or templated locators.
        }
        return stripHttpLocatorSecrets(trimmed);
    }

    private String stripHttpLocatorSecrets(String locator) {
        int end = locator.length();
        int queryIndex = locator.indexOf('?');
        int fragmentIndex = locator.indexOf('#');
        if (queryIndex >= 0) {
            end = Math.min(end, queryIndex);
        }
        if (fragmentIndex >= 0) {
            end = Math.min(end, fragmentIndex);
        }
        String sanitized = locator.substring(0, end);
        int schemeIndex = sanitized.indexOf("://");
        if (schemeIndex < 0) {
            return sanitized;
        }
        int authorityStart = schemeIndex + 3;
        int authorityEnd = sanitized.indexOf('/', authorityStart);
        if (authorityEnd < 0) {
            authorityEnd = sanitized.length();
        }
        int userInfoEnd = sanitized.lastIndexOf('@', authorityEnd);
        if (userInfoEnd < authorityStart) {
            return sanitized;
        }
        return sanitized.substring(0, authorityStart) + sanitized.substring(userInfoEnd + 1);
    }
}
