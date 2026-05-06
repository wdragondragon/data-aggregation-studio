package com.jdragon.studio.test;

import com.jdragon.studio.dto.enums.CollectionTaskType;
import com.jdragon.studio.dto.model.CollectionIncrementalDefinition;
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
import com.jdragon.studio.infra.service.PluginRuntimeOptionSchemaService;
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
                mock(EncryptionService.class),
                mockRuntimeOptionSchemaService());

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
                mock(EncryptionService.class),
                mockRuntimeOptionSchemaService());

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

    @Test
    void previewConfigShouldMergeAdvancedOptionsAndIncrementalCursor() {
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                mockDataSourceService(),
                mockDataModelService(),
                mock(EncryptionService.class),
                mockRuntimeOptionSchemaService());

        CollectionTaskDefinitionView definition = buildDefinition(null);
        CollectionTaskSourceBinding sourceBinding = definition.getSourceBindings().get(0);
        Map<String, Object> readerOptions = new LinkedHashMap<String, Object>();
        readerOptions.put("selectSql", "select source_col from source_table");
        readerOptions.put("test.param", "123");
        readerOptions.put("connect.host", "ignored_host");
        readerOptions.put("table", "ignored_table");
        sourceBinding.setReaderOptions(readerOptions);
        CollectionIncrementalDefinition incremental = new CollectionIncrementalDefinition();
        incremental.setEnabled(Boolean.TRUE);
        incremental.setIncrColumn("id");
        incremental.setIncrModel(">=");
        incremental.setPkValue(Long.valueOf(42L));
        sourceBinding.setIncremental(incremental);

        Map<String, Object> writerOptions = new LinkedHashMap<String, Object>();
        writerOptions.put("writeMode", "update");
        writerOptions.put("batchSize", Integer.valueOf(2048));
        writerOptions.put("pkColumn", Collections.singletonList("id"));
        definition.getTargetBinding().setWriterOptions(writerOptions);
        Map<String, Object> executionOptions = new LinkedHashMap<String, Object>();
        executionOptions.put("collectionMode", "INCREMENTAL");
        executionOptions.put("writeMode", "replace");
        definition.setExecutionOptions(executionOptions);

        Map<String, Object> config = assemblerService.assemble(definition);

        @SuppressWarnings("unchecked")
        Map<String, Object> reader = (Map<String, Object>) config.get("reader");
        @SuppressWarnings("unchecked")
        Map<String, Object> readerConfig = (Map<String, Object>) reader.get("config");
        assertEquals("source_table", readerConfig.get("table"));
        assertEquals("select source_col from source_table", readerConfig.get("selectSql"));
        assertFalse(readerConfig.containsKey("test.param"));
        assertEquals("123", castMap(readerConfig.get("test")).get("param"));
        assertFalse(castMap(readerConfig.get("connect")).containsKey("host"));
        assertEquals("id", readerConfig.get("incrColumn"));
        assertEquals(">=", readerConfig.get("incrModel"));
        assertEquals(Long.valueOf(42L), readerConfig.get("pkValue"));

        @SuppressWarnings("unchecked")
        Map<String, Object> writer = (Map<String, Object>) config.get("writer");
        @SuppressWarnings("unchecked")
        Map<String, Object> writerConfig = (Map<String, Object>) writer.get("config");
        assertEquals("update", writerConfig.get("writeMode"));
        assertEquals(Integer.valueOf(2048), writerConfig.get("batchSize"));
        assertIterableEquals(Collections.singletonList("id"), (List<?>) writerConfig.get("pkColumn"));
    }

    @Test
    void fusionReaderOptionsShouldUseDotPathConfigurationForPreviewAndRuntimeConfig() {
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                mockDataSourceService(),
                mockDataModelService(),
                mock(EncryptionService.class),
                mockRuntimeOptionSchemaService());

        CollectionTaskDefinitionView definition = buildFusionDefinition();
        Map<String, Object> fusionReaderOptions = new LinkedHashMap<String, Object>();
        fusionReaderOptions.put("performance.memoryLimitMB", Integer.valueOf(2048));
        fusionReaderOptions.put("adaptiveMerge.enabled", Boolean.TRUE);
        fusionReaderOptions.put("test.param", "nested");
        fusionReaderOptions.put("sources", "ignored");
        Map<String, Object> executionOptions = new LinkedHashMap<String, Object>();
        executionOptions.put("joinKeys", Collections.singletonList("id"));
        executionOptions.put("joinType", "LEFT");
        executionOptions.put("fusionReaderOptions", fusionReaderOptions);
        definition.setExecutionOptions(executionOptions);

        Map<String, Object> config = assemblerService.assemble(definition);

        @SuppressWarnings("unchecked")
        Map<String, Object> reader = (Map<String, Object>) config.get("reader");
        @SuppressWarnings("unchecked")
        Map<String, Object> readerConfig = (Map<String, Object>) reader.get("config");
        assertEquals("fusion", reader.get("type"));
        assertFalse(readerConfig.containsKey("test.param"));
        assertEquals("nested", castMap(readerConfig.get("test")).get("param"));
        assertEquals(Integer.valueOf(2048), castMap(readerConfig.get("performance")).get("memoryLimitMB"));
        assertEquals(Boolean.TRUE, castMap(readerConfig.get("adaptiveMerge")).get("enabled"));
        assertTrue(readerConfig.get("sources") instanceof List<?>);
    }

    @Test
    void fileReaderConfigShouldUseRuntimePathOptionsAndModelColumnIndexesWithoutIncremental() {
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                mockDataSourceService(),
                mockDataModelService(),
                mock(EncryptionService.class),
                mockRuntimeOptionSchemaService());

        CollectionTaskDefinitionView definition = new CollectionTaskDefinitionView();
        definition.setTaskType(CollectionTaskType.SINGLE_TABLE);

        CollectionTaskSourceBinding sourceBinding = new CollectionTaskSourceBinding();
        sourceBinding.setDatasourceId(3L);
        sourceBinding.setModelId(30L);
        sourceBinding.setSourceAlias("src1");
        Map<String, Object> readerOptions = new LinkedHashMap<String, Object>();
        readerOptions.put("rootPath", "/runtime/{dyn_timestamp}");
        readerOptions.put("partitionType", "regex");
        readerOptions.put("partition", "dt=.*/.*\\.csv");
        sourceBinding.setReaderOptions(readerOptions);
        CollectionIncrementalDefinition incremental = new CollectionIncrementalDefinition();
        incremental.setEnabled(Boolean.TRUE);
        incremental.setIncrColumn("id");
        incremental.setPkValue(Long.valueOf(99L));
        sourceBinding.setIncremental(incremental);
        definition.setSourceBindings(Collections.singletonList(sourceBinding));

        CollectionTaskTargetBinding targetBinding = new CollectionTaskTargetBinding();
        targetBinding.setDatasourceId(2L);
        targetBinding.setModelId(20L);
        definition.setTargetBinding(targetBinding);

        FieldMappingDefinition idMapping = new FieldMappingDefinition();
        idMapping.setSourceAlias("src1");
        idMapping.setSourceField("id");
        idMapping.setTargetField("target_col");
        FieldMappingDefinition nameMapping = new FieldMappingDefinition();
        nameMapping.setSourceAlias("src1");
        nameMapping.setSourceField("name");
        nameMapping.setTargetField("name");
        definition.setFieldMappings(Arrays.asList(idMapping, nameMapping));
        Map<String, Object> executionOptions = new LinkedHashMap<String, Object>();
        executionOptions.put("collectionMode", "INCREMENTAL");
        definition.setExecutionOptions(executionOptions);

        Map<String, Object> config = assemblerService.assemble(definition);

        @SuppressWarnings("unchecked")
        Map<String, Object> reader = (Map<String, Object>) config.get("reader");
        @SuppressWarnings("unchecked")
        Map<String, Object> readerConfig = (Map<String, Object>) reader.get("config");
        assertEquals("minio", reader.get("type"));
        assertEquals("/runtime/{dyn_timestamp}", readerConfig.get("rootPath"));
        assertEquals("regex", readerConfig.get("partitionType"));
        assertEquals("dt=.*/.*\\.csv", readerConfig.get("partition"));
        assertFalse(readerConfig.containsKey("table"));
        assertFalse(readerConfig.containsKey("incrColumn"));
        assertFalse(readerConfig.containsKey("pkValue"));

        @SuppressWarnings("unchecked")
        Map<String, Object> connect = (Map<String, Object>) readerConfig.get("connect");
        assertEquals("oss", connect.get("storageProvider"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) readerConfig.get("columns");
        assertEquals(Integer.valueOf(2), columns.get(0).get("index"));
        assertEquals(Integer.valueOf(1), columns.get(1).get("index"));
    }

    private DataSourceService mockDataSourceService() {
        DataSourceService service = mock(DataSourceService.class);
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(1L);
        datasource.setTypeCode("mysql8");
        datasource.setTechnicalMetadata(new LinkedHashMap<String, Object>());
        DataSourceDefinition minioDatasource = new DataSourceDefinition();
        minioDatasource.setId(3L);
        minioDatasource.setTypeCode("minio");
        Map<String, Object> minioMetadata = new LinkedHashMap<String, Object>();
        minioMetadata.put("storageProvider", "oss");
        minioMetadata.put("endpoint", "http://oss.example.com");
        minioMetadata.put("accessKey", "access");
        minioMetadata.put("secretKey", "secret");
        minioMetadata.put("bucket", "bucket");
        minioDatasource.setTechnicalMetadata(minioMetadata);
        when(service.getInternal(1L)).thenReturn(datasource);
        when(service.getInternal(2L)).thenReturn(datasource);
        when(service.getInternal(3L)).thenReturn(minioDatasource);
        return service;
    }

    private PluginRuntimeOptionSchemaService mockRuntimeOptionSchemaService() {
        PluginRuntimeOptionSchemaService service = mock(PluginRuntimeOptionSchemaService.class);
        when(service.resolvePluginType("mysql8", "reader")).thenReturn("mysql8");
        when(service.resolvePluginType("mysql8", "writer")).thenReturn("mysql8");
        when(service.resolvePluginType("minio", "reader")).thenReturn("minio");
        when(service.sourceCategory("mysql8")).thenReturn("DATABASE");
        when(service.sourceCategory("minio")).thenReturn("FILE_SYSTEM");
        when(service.reservedKeys("reader")).thenReturn(Arrays.asList("connect", "config", "table", "topic",
                "measurement", "columns", "sourceAlias", "sources", "join", "fieldMappings", "incrColumn", "incrModel", "pkValue"));
        when(service.reservedKeys("writer")).thenReturn(Arrays.asList("connect", "table", "topic", "measurement",
                "columns", "sourceAlias"));
        return service;
    }

    private DataModelService mockDataModelService() {
        DataModelService service = mock(DataModelService.class);
        DataModelDefinition sourceModel = buildModel(10L, "source_table");
        DataModelDefinition sourceModel2 = buildModel(11L, "source_table_2");
        DataModelDefinition targetModel = buildModel(20L, "target_table");
        DataModelDefinition fileModel = buildFileModel();
        when(service.get(10L)).thenReturn(sourceModel);
        when(service.get(11L)).thenReturn(sourceModel2);
        when(service.get(20L)).thenReturn(targetModel);
        when(service.get(30L)).thenReturn(fileModel);
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

    private DataModelDefinition buildFileModel() {
        DataModelDefinition model = new DataModelDefinition();
        model.setId(30L);
        model.setPhysicalLocator("/model-root");
        Map<String, Object> technicalMetadata = new LinkedHashMap<String, Object>();
        technicalMetadata.put("rootPath", "/model-root");
        technicalMetadata.put("partitionType", "glob");
        technicalMetadata.put("partition", "*.csv");
        technicalMetadata.put("fileType", "csv");
        technicalMetadata.put("encoding", "UTF-8");
        List<Map<String, Object>> columns = new ArrayList<Map<String, Object>>();
        Map<String, Object> id = column("id");
        id.put("index", Integer.valueOf(2));
        id.put("type", "LONG");
        columns.add(id);
        Map<String, Object> name = column("name");
        name.put("type", "STRING");
        columns.add(name);
        technicalMetadata.put("columns", columns);
        model.setTechnicalMetadata(technicalMetadata);
        return model;
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
        mapping.setTransformers(transformer == null ? Collections.<TransformerBinding>emptyList() : Collections.singletonList(transformer));
        definition.setFieldMappings(Collections.singletonList(mapping));
        return definition;
    }

    private CollectionTaskDefinitionView buildFusionDefinition() {
        CollectionTaskDefinitionView definition = new CollectionTaskDefinitionView();
        definition.setTaskType(CollectionTaskType.FUSION);

        CollectionTaskSourceBinding sourceBinding = new CollectionTaskSourceBinding();
        sourceBinding.setDatasourceId(1L);
        sourceBinding.setModelId(10L);
        sourceBinding.setSourceAlias("src1");

        CollectionTaskSourceBinding sourceBinding2 = new CollectionTaskSourceBinding();
        sourceBinding2.setDatasourceId(1L);
        sourceBinding2.setModelId(11L);
        sourceBinding2.setSourceAlias("src2");
        definition.setSourceBindings(Arrays.asList(sourceBinding, sourceBinding2));

        CollectionTaskTargetBinding targetBinding = new CollectionTaskTargetBinding();
        targetBinding.setDatasourceId(2L);
        targetBinding.setModelId(20L);
        Map<String, Object> writerOptions = new LinkedHashMap<String, Object>();
        writerOptions.put("writeMode", "insert");
        targetBinding.setWriterOptions(writerOptions);
        definition.setTargetBinding(targetBinding);

        FieldMappingDefinition mapping = new FieldMappingDefinition();
        mapping.setSourceAlias("src1");
        mapping.setSourceField("source_col");
        mapping.setTargetField("target_col");
        mapping.setTransformers(Collections.<TransformerBinding>emptyList());
        definition.setFieldMappings(Collections.singletonList(mapping));
        return definition;
    }

    @SuppressWarnings("unchecked")
    private List<Object> castList(Object value) {
        return value instanceof List ? (List<Object>) value : Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.<String, Object>emptyMap();
    }
}
