package com.jdragon.studio.test;

import com.jdragon.studio.dto.enums.CollectionTaskType;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.dto.model.CollectionTaskSourceBinding;
import com.jdragon.studio.dto.model.CollectionTaskTargetBinding;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.FieldMappingDefinition;
import com.jdragon.studio.dto.model.TransformerBinding;
import com.jdragon.studio.infra.service.CollectionTaskAssemblerService;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.EncryptionService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollectionTaskAssemblerServiceRegressionTest {

    @Test
    void previewConfigShouldOnlyExposeColumnIndexAndParasForTransformerParameters() {
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                mockDataSourceService(),
                mockDataModelService(),
                mock(EncryptionService.class));

        TransformerBinding transformer = new TransformerBinding();
        transformer.setTransformerCode("date_mask");
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put("paras", Arrays.asList("hide", 2, 4));
        parameters.put("hideOrShow", "hide");
        parameters.put("beforeNum", 2);
        parameters.put("centerNum", 4);
        transformer.setParameters(parameters);

        Map<String, Object> config = assemblerService.assemble(buildDefinition(transformer));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> transformers = (List<Map<String, Object>>) config.get("transformer");
        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeParameters = (Map<String, Object>) transformers.get(0).get("parameter");

        assertEquals(2, runtimeParameters.size());
        assertEquals(0, runtimeParameters.get("columnIndex"));
        assertIterableEquals(Arrays.asList("hide", 2, 4), castList(runtimeParameters.get("paras")));
        assertFalse(runtimeParameters.containsKey("hideOrShow"));
        assertFalse(runtimeParameters.containsKey("beforeNum"));
        assertFalse(runtimeParameters.containsKey("centerNum"));
    }

    @Test
    void previewConfigShouldFallbackToLegacyParameterOrderWhenParasMissing() {
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                mockDataSourceService(),
                mockDataModelService(),
                mock(EncryptionService.class));

        TransformerBinding transformer = new TransformerBinding();
        transformer.setTransformerCode("legacy_transformer");
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put("keep", "yes");
        parameters.put("count", 3);
        transformer.setParameters(parameters);

        Map<String, Object> config = assemblerService.assemble(buildDefinition(transformer));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> transformers = (List<Map<String, Object>>) config.get("transformer");
        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeParameters = (Map<String, Object>) transformers.get(0).get("parameter");

        assertEquals(2, runtimeParameters.size());
        assertEquals(0, runtimeParameters.get("columnIndex"));
        assertIterableEquals(Arrays.asList("yes", 3), castList(runtimeParameters.get("paras")));
        assertTrue(runtimeParameters.containsKey("paras"));
    }

    private DataSourceService mockDataSourceService() {
        DataSourceService service = mock(DataSourceService.class);
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(1L);
        datasource.setTypeCode("mysql8");
        datasource.setTechnicalMetadata(new LinkedHashMap<String, Object>());
        when(service.getInternal(1L)).thenReturn(datasource);
        when(service.getInternal(2L)).thenReturn(datasource);
        return service;
    }

    private DataModelService mockDataModelService() {
        DataModelService service = mock(DataModelService.class);
        DataModelDefinition sourceModel = buildModel(10L, "source_table");
        DataModelDefinition targetModel = buildModel(20L, "target_table");
        when(service.get(10L)).thenReturn(sourceModel);
        when(service.get(20L)).thenReturn(targetModel);
        return service;
    }

    private DataModelDefinition buildModel(Long id, String physicalLocator) {
        DataModelDefinition model = new DataModelDefinition();
        model.setId(id);
        model.setPhysicalLocator(physicalLocator);
        Map<String, Object> technicalMetadata = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> columns = new ArrayList<Map<String, Object>>();
        columns.add(column("target_col"));
        technicalMetadata.put("columns", columns);
        model.setTechnicalMetadata(technicalMetadata);
        return model;
    }

    private Map<String, Object> column(String name) {
        Map<String, Object> column = new LinkedHashMap<String, Object>();
        column.put("name", name);
        return column;
    }

    private CollectionTaskDefinitionView buildDefinition(TransformerBinding transformer) {
        CollectionTaskDefinitionView definition = new CollectionTaskDefinitionView();
        definition.setTaskType(CollectionTaskType.SINGLE_TABLE);

        CollectionTaskSourceBinding sourceBinding = new CollectionTaskSourceBinding();
        sourceBinding.setDatasourceId(1L);
        sourceBinding.setModelId(10L);
        sourceBinding.setSourceAlias("src1");
        definition.setSourceBindings(Collections.singletonList(sourceBinding));

        CollectionTaskTargetBinding targetBinding = new CollectionTaskTargetBinding();
        targetBinding.setDatasourceId(2L);
        targetBinding.setModelId(20L);
        definition.setTargetBinding(targetBinding);

        FieldMappingDefinition mapping = new FieldMappingDefinition();
        mapping.setSourceAlias("src1");
        mapping.setSourceField("source_col");
        mapping.setTargetField("target_col");
        mapping.setTransformers(Collections.singletonList(transformer));
        definition.setFieldMappings(Collections.singletonList(mapping));
        return definition;
    }

    @SuppressWarnings("unchecked")
    private List<Object> castList(Object value) {
        return value instanceof List ? (List<Object>) value : Collections.emptyList();
    }
}
