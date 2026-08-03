package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowConsistencyBindingValidatorTest {

    @Test
    void shouldValidateAllModelBindingsWithoutPersistedCredentials() {
        Fixture fixture = fixture();
        WorkflowNodeDefinition node = node(resourceConfig());

        assertDoesNotThrow(() -> fixture.validator.validate("tenant-a", Collections.singletonList(node)));

        verify(fixture.accessService).assertReadable(
                StudioConstants.RESOURCE_TYPE_DATA_MODEL, 10L, 101L, "Workflow data model was not found");
        verify(fixture.accessService).assertReadable(
                StudioConstants.RESOURCE_TYPE_DATA_MODEL, 10L, 102L, "Workflow data model was not found");
        verify(fixture.accessService).assertReadable(
                StudioConstants.RESOURCE_TYPE_DATA_MODEL, 10L, 103L, "Workflow data model was not found");
    }

    @Test
    void shouldRejectCredentialsInResourceBoundConfiguration() {
        Fixture fixture = fixture();
        Map<String, Object> config = resourceConfig();
        map(config.get("leftBinding")).put("password", "must-not-be-persisted");

        StudioException failure = assertThrows(StudioException.class,
                () -> fixture.validator.validate("tenant-a", Collections.singletonList(node(config))));

        assertTrue(failure.getMessage().contains("must not contain credentials"));
    }

    @Test
    void shouldPreserveLegacyRawConfigurationPath() {
        Fixture fixture = fixture();
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("reader", Map.of("type", "consistency", "config", Map.of("password", "legacy")));
        config.put("writer", Map.of("type", "console", "config", Collections.emptyMap()));

        assertDoesNotThrow(() -> fixture.validator.validate(
                "tenant-a", Collections.singletonList(node(config))));
    }

    private Fixture fixture() {
        DataModelMapper mapper = mock(DataModelMapper.class);
        ProjectResourceAccessService access = mock(ProjectResourceAccessService.class);
        when(mapper.selectById(101L)).thenReturn(model(101L, 1L, "left_table", "id", "value"));
        when(mapper.selectById(102L)).thenReturn(model(102L, 2L, "right_table", "id", "value"));
        when(mapper.selectById(103L)).thenReturn(model(103L, 3L, "diff_table",
                "rule_id", "record_id", "match_keys", "conflict_type", "differences", "payload"));
        return new Fixture(new WorkflowConsistencyBindingValidator(mapper, access), access);
    }

    private WorkflowNodeDefinition node(Map<String, Object> config) {
        WorkflowNodeDefinition node = new WorkflowNodeDefinition();
        node.setNodeType(NodeType.CONSISTENCY);
        node.setConfig(config);
        return node;
    }

    private Map<String, Object> resourceConfig() {
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("ruleId", "studio-consistency");
        config.put("matchKeys", Collections.singletonList("id"));
        config.put("compareFields", Collections.singletonList("value"));
        config.put("leftBinding", binding(1L, 101L));
        config.put("rightBinding", binding(2L, 102L));
        config.put("outputBinding", binding(3L, 103L));
        return config;
    }

    private Map<String, Object> binding(Long datasourceId, Long modelId) {
        Map<String, Object> binding = new LinkedHashMap<String, Object>();
        binding.put("datasourceId", datasourceId);
        binding.put("modelId", modelId);
        return binding;
    }

    private DataModelEntity model(Long id, Long datasourceId, String locator, String... fields) {
        DataModelEntity model = new DataModelEntity();
        model.setId(id);
        model.setTenantId("tenant-a");
        model.setProjectId(10L);
        model.setDatasourceId(datasourceId);
        model.setPhysicalLocator(locator);
        List<Map<String, Object>> columns = new ArrayList<Map<String, Object>>();
        for (String field : fields) {
            columns.add(Map.of("name", field));
        }
        model.setTechnicalMetadata(Map.of("columns", columns));
        return model;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static class Fixture {
        private final WorkflowConsistencyBindingValidator validator;
        private final ProjectResourceAccessService accessService;

        private Fixture(WorkflowConsistencyBindingValidator validator,
                        ProjectResourceAccessService accessService) {
            this.validator = validator;
            this.accessService = accessService;
        }
    }
}
