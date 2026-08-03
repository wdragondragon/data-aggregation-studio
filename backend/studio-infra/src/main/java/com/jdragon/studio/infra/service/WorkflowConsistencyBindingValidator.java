package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Save-time validation for credential-free, resource-bound consistency nodes. */
@Service
public class WorkflowConsistencyBindingValidator {

    private static final List<String> OUTPUT_COLUMNS = Arrays.asList(
            "rule_id", "record_id", "match_keys", "conflict_type", "differences", "payload");

    private final DataModelMapper dataModelMapper;
    private final ProjectResourceAccessService projectResourceAccessService;

    public WorkflowConsistencyBindingValidator(DataModelMapper dataModelMapper,
                                               ProjectResourceAccessService projectResourceAccessService) {
        this.dataModelMapper = dataModelMapper;
        this.projectResourceAccessService = projectResourceAccessService;
    }

    public void validate(String workflowTenantId, List<WorkflowNodeDefinition> nodes) {
        if (nodes == null) {
            return;
        }
        for (WorkflowNodeDefinition node : nodes) {
            if (node == null || node.getNodeType() != NodeType.CONSISTENCY) {
                continue;
            }
            validateNode(workflowTenantId, node);
        }
    }

    private void validateNode(String workflowTenantId, WorkflowNodeDefinition node) {
        Map<String, Object> config = node.getConfig() == null
                ? new LinkedHashMap<String, Object>() : node.getConfig();
        boolean hasRawReader = config.get("reader") instanceof Map<?, ?>;
        boolean hasRawWriter = config.get("writer") instanceof Map<?, ?>;
        boolean hasBindings = config.containsKey("leftBinding")
                || config.containsKey("rightBinding") || config.containsKey("outputBinding");

        if (!hasBindings) {
            // Existing raw DataAggregation jobs remain compatible.
            return;
        }
        if (hasRawReader || hasRawWriter) {
            throw badRequest("Consistency resource bindings cannot be mixed with raw reader/writer configuration");
        }
        assertNoSensitiveKeys(config);

        List<String> matchKeys = requiredStringList(config.get("matchKeys"),
                "Consistency matchKeys are required");
        List<String> compareFields = requiredStringList(config.get("compareFields"),
                "Consistency compareFields are required");
        requiredText(config.get("ruleId"), "Consistency ruleId is required");

        DataModelEntity left = validateBinding(workflowTenantId, config, "leftBinding", matchKeys, compareFields);
        DataModelEntity right = validateBinding(workflowTenantId, config, "rightBinding", matchKeys, compareFields);
        DataModelEntity output = validateBinding(workflowTenantId, config, "outputBinding", OUTPUT_COLUMNS);
        String leftAlias = sourceAlias(config, "leftBinding", "left");
        String rightAlias = sourceAlias(config, "rightBinding", "right");
        if (leftAlias.equals(rightAlias)) {
            throw badRequest("Consistency source aliases must be different");
        }
        if (left.getId().equals(right.getId())) {
            throw badRequest("Consistency leftBinding and rightBinding must reference different models");
        }
        if (output.getId().equals(left.getId()) || output.getId().equals(right.getId())) {
            throw badRequest("Consistency outputBinding must reference a model separate from both sources");
        }
    }

    private String sourceAlias(Map<String, Object> config, String bindingName, String defaultAlias) {
        String alias = text(objectMap(config.get(bindingName)).get("sourceAlias"));
        return alias == null ? defaultAlias : alias;
    }

    @SafeVarargs
    private final DataModelEntity validateBinding(String workflowTenantId,
                                            Map<String, Object> config,
                                            String bindingName,
                                            List<String>... requiredFieldGroups) {
        Map<String, Object> binding = objectMap(config.get(bindingName));
        if (binding.isEmpty()) {
            throw badRequest("Consistency " + bindingName + " is required");
        }
        Long datasourceId = requiredLong(binding.get("datasourceId"),
                "Consistency " + bindingName + " datasourceId is required");
        Long modelId = requiredLong(binding.get("modelId"),
                "Consistency " + bindingName + " modelId is required");
        DataModelEntity model = dataModelMapper.selectById(modelId);
        if (model == null || workflowTenantId == null || !workflowTenantId.equals(model.getTenantId())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Workflow data model was not found");
        }
        projectResourceAccessService.assertReadable(StudioConstants.RESOURCE_TYPE_DATA_MODEL,
                model.getProjectId(), model.getId(), "Workflow data model was not found");
        if (!datasourceId.equals(model.getDatasourceId())) {
            throw badRequest("Consistency " + bindingName + " model does not belong to datasource");
        }
        if (model.getPhysicalLocator() == null || model.getPhysicalLocator().trim().isEmpty()) {
            throw badRequest("Consistency " + bindingName + " model physical locator is required");
        }
        Set<String> availableFields = modelFields(model);
        if (!availableFields.isEmpty()) {
            for (List<String> requiredFields : requiredFieldGroups) {
                List<String> missing = new ArrayList<String>();
                for (String requiredField : requiredFields) {
                    if (!availableFields.contains(requiredField)) {
                        missing.add(requiredField);
                    }
                }
                if (!missing.isEmpty()) {
                    throw badRequest("Consistency " + bindingName + " fields were not found in model: "
                            + String.join(", ", missing));
                }
            }
        }
        return model;
    }

    private void assertNoSensitiveKeys(Object value) {
        if (value instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                String normalized = key.toLowerCase(Locale.ENGLISH).replace("_", "").replace("-", "");
                if (normalized.contains("password") || normalized.contains("secret")
                        || normalized.contains("token") || normalized.contains("accesskey")
                        || normalized.contains("credential")) {
                    throw badRequest("Consistency resource bindings must not contain credentials");
                }
                assertNoSensitiveKeys(entry.getValue());
            }
        } else if (value instanceof Collection<?>) {
            for (Object item : (Collection<?>) value) {
                assertNoSensitiveKeys(item);
            }
        }
    }

    private Set<String> modelFields(DataModelEntity model) {
        Set<String> result = new LinkedHashSet<String>();
        Object columns = model.getTechnicalMetadata() == null ? null : model.getTechnicalMetadata().get("columns");
        if (!(columns instanceof List<?>)) {
            return result;
        }
        for (Object column : (List<?>) columns) {
            if (column instanceof Map<?, ?>) {
                Map<?, ?> map = (Map<?, ?>) column;
                String name = firstText(map.get("name"), map.get("columnName"), map.get("fieldName"));
                if (name != null) {
                    result.add(name);
                }
            } else {
                String name = text(column);
                if (name != null) {
                    result.add(name);
                }
            }
        }
        return result;
    }

    private List<String> requiredStringList(Object value, String message) {
        List<String> result = new ArrayList<String>();
        if (value instanceof Collection<?>) {
            for (Object item : (Collection<?>) value) {
                String text = text(item);
                if (text != null && !result.contains(text)) {
                    result.add(text);
                }
            }
        } else if (value instanceof String) {
            for (String item : ((String) value).split(",")) {
                String text = text(item);
                if (text != null && !result.contains(text)) {
                    result.add(text);
                }
            }
        }
        if (result.isEmpty()) {
            throw badRequest(message);
        }
        return result;
    }

    private Long requiredLong(Object value, String message) {
        if (value instanceof Number) {
            return Long.valueOf(((Number) value).longValue());
        }
        String text = text(value);
        if (text != null) {
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException ignored) {
                // handled below
            }
        }
        throw badRequest(message);
    }

    private String requiredText(Object value, String message) {
        String text = text(value);
        if (text == null) {
            throw badRequest(message);
        }
        return text;
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            String candidate = text(value);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private Map<String, Object> objectMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (!(value instanceof Map<?, ?>)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private StudioException badRequest(String message) {
        return new StudioException(StudioErrorCode.BAD_REQUEST, message);
    }
}
