package com.jdragon.studio.test;

import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.infra.service.CollectionTaskAssemblerService;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.EncryptionService;
import com.jdragon.studio.infra.service.PluginRuntimeOptionSchemaService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsistencyWorkflowAssemblerRegressionTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldHydrateCredentialFreeBindingsOnlyAtExecutionTime() {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService dataModelService = mock(DataModelService.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        PluginRuntimeOptionSchemaService schemaService = mock(PluginRuntimeOptionSchemaService.class);
        when(encryptionService.decrypt("cipher")).thenReturn("runtime-secret");
        when(schemaService.resolveSourcePlugin("mysql8")).thenReturn("mysql8");
        when(schemaService.resolvePluginType("mysql8", "writer")).thenReturn("mysql8");
        when(schemaService.sourceCategory("mysql8")).thenReturn("DATABASE");
        when(schemaService.reservedKeys("writer")).thenReturn(Collections.emptyList());

        when(dataSourceService.requireRunnableForExecution(1L)).thenReturn(datasource(1L));
        when(dataSourceService.requireRunnableForExecution(2L)).thenReturn(datasource(2L));
        when(dataSourceService.requireRunnableForExecution(3L)).thenReturn(datasource(3L));
        when(dataModelService.get(10L)).thenReturn(model(10L, 1L, "left_table", "id", "value"));
        when(dataModelService.get(11L)).thenReturn(model(11L, 2L, "right_table", "id", "value"));
        when(dataModelService.get(20L)).thenReturn(model(20L, 3L, "diff_table",
                "rule_id", "record_id", "match_keys", "conflict_type", "differences", "payload"));

        CollectionTaskAssemblerService assembler = new CollectionTaskAssemblerService(
                dataSourceService, dataModelService, encryptionService, schemaService);
        Map<String, Object> stored = resourceConfig();
        String before = stored.toString();

        Map<String, Object> runtime = assembler.assembleConsistency(stored);

        assertEquals(before, stored.toString());
        assertFalse(stored.toString().contains("runtime-secret"));
        Map<String, Object> reader = (Map<String, Object>) runtime.get("reader");
        assertEquals("consistency", reader.get("type"));
        Map<String, Object> readerConfig = (Map<String, Object>) reader.get("config");
        assertEquals(Boolean.FALSE, readerConfig.get("autoApplyResolutions"));
        List<Map<String, Object>> sources = (List<Map<String, Object>>) readerConfig.get("dataSources");
        assertEquals("mysql8", sources.get(0).get("datasourceType"));
        assertEquals("mysql8", sources.get(0).get("pluginName"));
        assertEquals("left_table", sources.get(0).get("tableName"));
        assertEquals("runtime-secret",
                ((Map<String, Object>) sources.get(0).get("connectionConfig")).get("password"));
        Map<String, Object> writer = (Map<String, Object>) runtime.get("writer");
        Map<String, Object> writerConfig = (Map<String, Object>) writer.get("config");
        assertEquals(Arrays.asList("rule_id", "record_id", "match_keys", "conflict_type", "differences", "payload"),
                writerConfig.get("columns"));
        assertEquals("runtime-secret",
                ((Map<String, Object>) writerConfig.get("connect")).get("password"));
    }

    @Test
    void shouldKeepLegacyRawReaderWriterConfigurationCompatible() {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        CollectionTaskAssemblerService assembler = new CollectionTaskAssemblerService(
                dataSourceService,
                mock(DataModelService.class),
                mock(EncryptionService.class),
                mock(PluginRuntimeOptionSchemaService.class));
        Map<String, Object> raw = new LinkedHashMap<String, Object>();
        raw.put("reader", Map.of("type", "consistency", "config", Map.of("ruleId", "legacy")));
        raw.put("writer", Map.of("type", "console", "config", Collections.emptyMap()));

        assertEquals(raw, assembler.assembleConsistency(raw));
        verify(dataSourceService, never()).requireRunnableForExecution(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void shouldRejectBindingWhenModelBelongsToAnotherDatasource() {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService dataModelService = mock(DataModelService.class);
        when(dataSourceService.requireRunnableForExecution(1L)).thenReturn(datasource(1L));
        when(dataModelService.get(10L)).thenReturn(model(10L, 99L, "left_table", "id", "value"));
        CollectionTaskAssemblerService assembler = new CollectionTaskAssemblerService(
                dataSourceService, dataModelService, mock(EncryptionService.class),
                mock(PluginRuntimeOptionSchemaService.class));

        StudioException failure = assertThrows(StudioException.class,
                () -> assembler.assembleConsistency(resourceConfig()));

        assertTrue(failure.getMessage().contains("does not belong"));
    }

    private Map<String, Object> resourceConfig() {
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("ruleId", "studio-e2e-consistency");
        config.put("ruleName", "Studio E2E consistency");
        config.put("matchKeys", Collections.singletonList("id"));
        config.put("compareFields", Collections.singletonList("value"));
        config.put("leftBinding", binding(1L, 10L, "left"));
        config.put("rightBinding", binding(2L, 11L, "right"));
        config.put("outputBinding", binding(3L, 20L, null));
        return config;
    }

    private Map<String, Object> binding(Long datasourceId, Long modelId, String alias) {
        Map<String, Object> binding = new LinkedHashMap<String, Object>();
        binding.put("datasourceId", datasourceId);
        binding.put("modelId", modelId);
        if (alias != null) {
            binding.put("sourceAlias", alias);
        }
        return binding;
    }

    private DataSourceDefinition datasource(Long id) {
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(id);
        datasource.setTypeCode("mysql8");
        datasource.setEnabled(Boolean.TRUE);
        datasource.setExecutable(Boolean.TRUE);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("host", "127.0.0.1");
        metadata.put("database", "studio_e2e");
        metadata.put("userName", "studio");
        metadata.put("password", "ENC(cipher)");
        datasource.setTechnicalMetadata(metadata);
        return datasource;
    }

    private DataModelDefinition model(Long id, Long datasourceId, String locator, String... fields) {
        DataModelDefinition model = new DataModelDefinition();
        model.setId(id);
        model.setDatasourceId(datasourceId);
        model.setName(locator);
        model.setPhysicalLocator(locator);
        List<Map<String, Object>> columns = new java.util.ArrayList<Map<String, Object>>();
        for (String field : fields) {
            columns.add(Map.of("name", field));
        }
        model.setTechnicalMetadata(Map.of("columns", columns));
        return model;
    }
}
