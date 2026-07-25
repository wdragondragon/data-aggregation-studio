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
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.service.CollectionTaskAssemblerService;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.EncryptionService;
import com.jdragon.studio.infra.service.HttpReaderOptionSecurityService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollectionTaskAssemblerServiceRegressionTest extends CollectionTaskAssemblerTestSupport {

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
    void mysqlWriterShouldEnableJdbcBatchRewriteByDefault() {
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                mockDataSourceService(),
                mockDataModelService(),
                mock(EncryptionService.class),
                mockRuntimeOptionSchemaService());

        Map<String, Object> config = assemblerService.assemble(buildDefinition(null));

        @SuppressWarnings("unchecked")
        Map<String, Object> writer = (Map<String, Object>) config.get("writer");
        @SuppressWarnings("unchecked")
        Map<String, Object> writerConfig = (Map<String, Object>) writer.get("config");
        @SuppressWarnings("unchecked")
        Map<String, Object> connect = (Map<String, Object>) writerConfig.get("connect");

        assertEquals("{\"rewriteBatchedStatements\":\"true\"}", connect.get("other"));
    }

    private EncryptionService testEncryptionService() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setEncryptionSecret("collection-task-assembler-regression-test");
        return new EncryptionService(properties);
    }

    @Test
    void previewShouldUseMaskedDatasourceViewsWithoutDecryptingInternalConnections() {
        EncryptionService encryptionService = testEncryptionService();
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataSourceDefinition internalSource = datasourceWithPassword(
                1L, "ENC(" + encryptionService.encrypt("source-secret") + ")");
        DataSourceDefinition internalTarget = datasourceWithPassword(
                2L, "ENC(" + encryptionService.encrypt("target-secret") + ")");
        DataSourceDefinition maskedSource = datasourceWithPassword(1L, "so********et");
        DataSourceDefinition maskedTarget = datasourceWithPassword(2L, "ta********et");
        when(dataSourceService.getInternal(1L)).thenReturn(internalSource);
        when(dataSourceService.getInternal(2L)).thenReturn(internalTarget);
        when(dataSourceService.get(1L)).thenReturn(maskedSource);
        when(dataSourceService.get(2L)).thenReturn(maskedTarget);
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                dataSourceService,
                mockDataModelService(),
                encryptionService,
                mockRuntimeOptionSchemaService());

        Map<String, Object> preview = assemblerService.assemblePreview(buildDefinition(null));

        String serializedPreview = String.valueOf(preview);
        assertFalse(serializedPreview.contains("source-secret"));
        assertFalse(serializedPreview.contains("target-secret"));
        assertTrue(serializedPreview.contains("so********et"));
        assertTrue(serializedPreview.contains("ta********et"));
        verify(dataSourceService, never()).getInternal(1L);
        verify(dataSourceService, never()).getInternal(2L);

        String execution = String.valueOf(assemblerService.assemble(buildDefinition(null)));
        assertTrue(execution.contains("source-secret"));
        assertTrue(execution.contains("target-secret"));
    }

    private DataSourceDefinition datasourceWithPassword(Long id, String password) {
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(id);
        datasource.setTypeCode("mysql8");
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("host", "db.internal");
        metadata.put("password", password);
        datasource.setTechnicalMetadata(metadata);
        return datasource;
    }

    @Test
    void mysqlWriterShouldKeepExistingBatchRewriteOption() {
        DataSourceService dataSourceService = mockDataSourceService();
        DataSourceDefinition targetDatasource = new DataSourceDefinition();
        targetDatasource.setId(2L);
        targetDatasource.setTypeCode("mysql8");
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("other", "{\"serverTimezone\":\"Asia/Shanghai\",\"rewriteBatchedStatements\":\"false\"}");
        targetDatasource.setTechnicalMetadata(metadata);
        when(dataSourceService.getInternal(2L)).thenReturn(targetDatasource);
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                dataSourceService,
                mockDataModelService(),
                mock(EncryptionService.class),
                mockRuntimeOptionSchemaService());

        Map<String, Object> config = assemblerService.assemble(buildDefinition(null));

        @SuppressWarnings("unchecked")
        Map<String, Object> writer = (Map<String, Object>) config.get("writer");
        @SuppressWarnings("unchecked")
        Map<String, Object> writerConfig = (Map<String, Object>) writer.get("config");
        @SuppressWarnings("unchecked")
        Map<String, Object> connect = (Map<String, Object>) writerConfig.get("connect");

        assertEquals("{\"serverTimezone\":\"Asia/Shanghai\",\"rewriteBatchedStatements\":\"false\"}", connect.get("other"));
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
        executionOptions.put("joinKeys", Collections.singletonList("target_col"));
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
    void fusionReaderSourcesShouldIncludeJoinKeysEvenWhenNotMappedToTargetFields() {
        DataModelService dataModelService = mock(DataModelService.class);
        when(dataModelService.get(10L)).thenReturn(buildModelWithColumns(10L, "order_table", "order_id", "order_amount"));
        when(dataModelService.get(11L)).thenReturn(buildModelWithColumns(11L, "payment_table", "order_id", "payment_amount"));
        when(dataModelService.get(20L)).thenReturn(buildModelWithColumns(20L, "target_table", "order_amount", "payment_amount"));
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                mockDataSourceService(),
                dataModelService,
                mock(EncryptionService.class),
                mockRuntimeOptionSchemaService());

        CollectionTaskDefinitionView definition = new CollectionTaskDefinitionView();
        definition.setTaskType(CollectionTaskType.FUSION);
        CollectionTaskSourceBinding orderSource = new CollectionTaskSourceBinding();
        orderSource.setDatasourceId(1L);
        orderSource.setModelId(10L);
        orderSource.setSourceAlias("orders");
        CollectionTaskSourceBinding paymentSource = new CollectionTaskSourceBinding();
        paymentSource.setDatasourceId(1L);
        paymentSource.setModelId(11L);
        paymentSource.setSourceAlias("payments");
        definition.setSourceBindings(Arrays.asList(orderSource, paymentSource));
        CollectionTaskTargetBinding targetBinding = new CollectionTaskTargetBinding();
        targetBinding.setDatasourceId(2L);
        targetBinding.setModelId(20L);
        definition.setTargetBinding(targetBinding);

        FieldMappingDefinition orderAmountMapping = new FieldMappingDefinition();
        orderAmountMapping.setSourceAlias("orders");
        orderAmountMapping.setSourceField("order_amount");
        orderAmountMapping.setTargetField("order_amount");
        FieldMappingDefinition paymentAmountMapping = new FieldMappingDefinition();
        paymentAmountMapping.setSourceAlias("payments");
        paymentAmountMapping.setSourceField("payment_amount");
        paymentAmountMapping.setTargetField("payment_amount");
        definition.setFieldMappings(Arrays.asList(orderAmountMapping, paymentAmountMapping));
        Map<String, Object> executionOptions = new LinkedHashMap<String, Object>();
        executionOptions.put("joinKeys", Collections.singletonList("order_id"));
        definition.setExecutionOptions(executionOptions);

        Map<String, Object> config = assemblerService.assemble(definition);

        @SuppressWarnings("unchecked")
        Map<String, Object> reader = (Map<String, Object>) config.get("reader");
        @SuppressWarnings("unchecked")
        Map<String, Object> readerConfig = (Map<String, Object>) reader.get("config");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) readerConfig.get("sources");
        assertIterableEquals(Arrays.asList("order_amount", "order_id"), (List<?>) sources.get(0).get("columns"));
        assertIterableEquals(Arrays.asList("payment_amount", "order_id"), (List<?>) sources.get(1).get("columns"));

        @SuppressWarnings("unchecked")
        Map<String, Object> writer = (Map<String, Object>) config.get("writer");
        @SuppressWarnings("unchecked")
        Map<String, Object> writerConfig = (Map<String, Object>) writer.get("config");
        assertIterableEquals(Arrays.asList("order_amount", "payment_amount"), (List<?>) writerConfig.get("columns"));
    }

    @Test
    void fusionHttpSourceShouldMergeModelReaderOptionsBeforeTaskOptions() {
        DataModelService dataModelService = mock(DataModelService.class);
        when(dataModelService.get(10L)).thenReturn(buildModelWithColumns(10L, "customer_table", "id", "source_col"));
        DataModelDefinition httpModel = buildHttpModel(false);
        Map<String, Object> modelReaderOptions = new LinkedHashMap<String, Object>();
        modelReaderOptions.put("url", "http://evil.example.com/model");
        modelReaderOptions.put("mode", "POST");
        modelReaderOptions.put("header", "{\"X-Model\":\"model\"}");
        modelReaderOptions.put("params", "{\"customer\":\"{dyn_page}\"}");
        modelReaderOptions.put("pageRead", Boolean.TRUE);
        modelReaderOptions.put("pageSize", Integer.valueOf(100));
        httpModel.getTechnicalMetadata().put("readerOptions", modelReaderOptions);
        when(dataModelService.get(40L)).thenReturn(httpModel);
        when(dataModelService.get(20L)).thenReturn(buildModelWithColumns(20L, "target_table", "target_col", "name"));
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                mockDataSourceService(),
                dataModelService,
                mock(EncryptionService.class),
                mockRuntimeOptionSchemaService());

        CollectionTaskDefinitionView definition = new CollectionTaskDefinitionView();
        definition.setTaskType(CollectionTaskType.FUSION);
        CollectionTaskSourceBinding mysqlSource = new CollectionTaskSourceBinding();
        mysqlSource.setDatasourceId(1L);
        mysqlSource.setModelId(10L);
        mysqlSource.setSourceAlias("mysql_customer");
        CollectionTaskSourceBinding httpSource = new CollectionTaskSourceBinding();
        httpSource.setDatasourceId(4L);
        httpSource.setModelId(40L);
        httpSource.setSourceAlias("http_risk");
        Map<String, Object> taskReaderOptions = new LinkedHashMap<String, Object>();
        taskReaderOptions.put("header", "{\"X-Task\":\"task\"}");
        taskReaderOptions.put("pageSize", Integer.valueOf(50));
        httpSource.setReaderOptions(taskReaderOptions);
        definition.setSourceBindings(Arrays.asList(mysqlSource, httpSource));
        CollectionTaskTargetBinding targetBinding = new CollectionTaskTargetBinding();
        targetBinding.setDatasourceId(2L);
        targetBinding.setModelId(20L);
        definition.setTargetBinding(targetBinding);

        FieldMappingDefinition targetId = new FieldMappingDefinition();
        targetId.setSourceAlias("http_risk");
        targetId.setSourceField("id");
        targetId.setTargetField("target_col");
        FieldMappingDefinition targetName = new FieldMappingDefinition();
        targetName.setSourceAlias("http_risk");
        targetName.setSourceField("name");
        targetName.setTargetField("name");
        definition.setFieldMappings(Arrays.asList(targetId, targetName));
        Map<String, Object> executionOptions = new LinkedHashMap<String, Object>();
        executionOptions.put("joinKeys", Collections.singletonList("id"));
        definition.setExecutionOptions(executionOptions);

        Map<String, Object> config = assemblerService.assemble(definition);

        @SuppressWarnings("unchecked")
        Map<String, Object> reader = (Map<String, Object>) config.get("reader");
        @SuppressWarnings("unchecked")
        Map<String, Object> readerConfig = (Map<String, Object>) reader.get("config");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) readerConfig.get("sources");
        Map<String, Object> httpSourceConfig = sources.get(1);
        assertEquals("http", httpSourceConfig.get("type"));
        assertEquals("http://api.example.com/base/users", httpSourceConfig.get("url"));
        assertEquals("GET", httpSourceConfig.get("mode"));
        assertEquals("{\"X-Task\":\"task\",\"X-Model\":\"model\"}", httpSourceConfig.get("header"));
        assertEquals("{\"customer\":\"{dyn_page}\"}", httpSourceConfig.get("params"));
        assertEquals(Boolean.TRUE, httpSourceConfig.get("pageRead"));
        assertEquals(Integer.valueOf(50), httpSourceConfig.get("pageSize"));
    }

    @Test
    void odpsReaderAndWriterOptionsShouldUseGenericJobConfigAssembly() {
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                mockDataSourceService(),
                mockDataModelService(),
                mock(EncryptionService.class),
                mockRuntimeOptionSchemaService());

        CollectionTaskDefinitionView definition = new CollectionTaskDefinitionView();
        definition.setTaskType(CollectionTaskType.SINGLE_TABLE);

        CollectionTaskSourceBinding sourceBinding = new CollectionTaskSourceBinding();
        sourceBinding.setDatasourceId(5L);
        sourceBinding.setModelId(50L);
        sourceBinding.setSourceAlias("src1");
        Map<String, Object> readerOptions = new LinkedHashMap<String, Object>();
        readerOptions.put("readMode", "sql");
        readerOptions.put("selectSql", "select id, name from odps_source_table");
        readerOptions.put("partitionSpec", "dt='20260605'");
        readerOptions.put("includePartitionColumns", Boolean.TRUE);
        readerOptions.put("offset", Long.valueOf(10L));
        readerOptions.put("maxRows", Long.valueOf(100L));
        readerOptions.put("connect.host", "ignored-host");
        readerOptions.put("columns", Collections.singletonList("ignored"));
        sourceBinding.setReaderOptions(readerOptions);
        definition.setSourceBindings(Collections.singletonList(sourceBinding));

        CollectionTaskTargetBinding targetBinding = new CollectionTaskTargetBinding();
        targetBinding.setDatasourceId(5L);
        targetBinding.setModelId(51L);
        Map<String, Object> writerOptions = new LinkedHashMap<String, Object>();
        writerOptions.put("writeMode", "overwrite");
        writerOptions.put("partitionColumns", Collections.singletonList("dt"));
        writerOptions.put("batchSize", Integer.valueOf(2000));
        writerOptions.put("emptyAsNull", Boolean.TRUE);
        writerOptions.put("autoCreatePartition", Boolean.TRUE);
        writerOptions.put("preSql", "set odps.sql.allow.fullscan=true;");
        targetBinding.setWriterOptions(writerOptions);
        definition.setTargetBinding(targetBinding);

        FieldMappingDefinition idMapping = new FieldMappingDefinition();
        idMapping.setSourceAlias("src1");
        idMapping.setSourceField("id");
        idMapping.setTargetField("id");
        FieldMappingDefinition nameMapping = new FieldMappingDefinition();
        nameMapping.setSourceAlias("src1");
        nameMapping.setSourceField("name");
        nameMapping.setTargetField("name");
        FieldMappingDefinition partitionMapping = new FieldMappingDefinition();
        partitionMapping.setSourceAlias("src1");
        partitionMapping.setSourceField("dt");
        partitionMapping.setTargetField("dt");
        definition.setFieldMappings(Arrays.asList(idMapping, nameMapping, partitionMapping));

        Map<String, Object> config = assemblerService.assemble(definition);

        @SuppressWarnings("unchecked")
        Map<String, Object> reader = (Map<String, Object>) config.get("reader");
        @SuppressWarnings("unchecked")
        Map<String, Object> readerConfig = (Map<String, Object>) reader.get("config");
        assertEquals("odps", reader.get("type"));
        assertEquals("odps_source_table", readerConfig.get("table"));
        assertEquals("sql", readerConfig.get("readMode"));
        assertEquals("select id, name from odps_source_table", readerConfig.get("selectSql"));
        assertEquals("dt='20260605'", readerConfig.get("partitionSpec"));
        assertEquals(Boolean.TRUE, readerConfig.get("includePartitionColumns"));
        assertEquals(Long.valueOf(10L), readerConfig.get("offset"));
        assertEquals(Long.valueOf(100L), readerConfig.get("maxRows"));
        assertIterableEquals(Arrays.asList("id", "name", "dt"), (List<?>) readerConfig.get("columns"));
        assertEquals("http://service.cn-hangzhou.maxcompute.aliyun.com/api", castMap(readerConfig.get("connect")).get("host"));
        assertEquals("true", castMap(castMap(readerConfig.get("connect")).get("extraParams")).get("odps.sql.allow.fullscan"));

        @SuppressWarnings("unchecked")
        Map<String, Object> writer = (Map<String, Object>) config.get("writer");
        @SuppressWarnings("unchecked")
        Map<String, Object> writerConfig = (Map<String, Object>) writer.get("config");
        assertEquals("odps", writer.get("type"));
        assertEquals("odps_target_pt", writerConfig.get("table"));
        assertIterableEquals(Arrays.asList("id", "name", "dt"), (List<?>) writerConfig.get("columns"));
        assertEquals("overwrite", writerConfig.get("writeMode"));
        assertIterableEquals(Collections.singletonList("dt"), (List<?>) writerConfig.get("partitionColumns"));
        assertEquals(Integer.valueOf(2000), writerConfig.get("batchSize"));
        assertEquals(Boolean.TRUE, writerConfig.get("emptyAsNull"));
        assertEquals(Boolean.TRUE, writerConfig.get("autoCreatePartition"));
        assertEquals("set odps.sql.allow.fullscan=true;", writerConfig.get("preSql"));
    }

    @Test
    void fileReaderConfigShouldUseModelPathOptionsAndModelColumnIndexesWithoutIncremental() {
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
        readerOptions.put("rootPath", "/runtime/$getCurrentTime(yyyyMMdd,0)");
        readerOptions.put("partitionType", "regex");
        readerOptions.put("partition", "dt=.*/.*\\.csv");
        readerOptions.put("fileType", "efile");
        readerOptions.put("encoding", "GBK");
        readerOptions.put("delimiter", "|");
        readerOptions.put("hasHeader", Boolean.FALSE);
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
        assertEquals("/model-root", readerConfig.get("rootPath"));
        assertEquals("glob", readerConfig.get("partitionType"));
        assertEquals("*.csv", readerConfig.get("partition"));
        assertEquals("csv", readerConfig.get("fileType"));
        assertEquals("UTF-8", readerConfig.get("encoding"));
        assertEquals(",", readerConfig.get("delimiter"));
        assertEquals(Boolean.FALSE, readerConfig.get("hasHeader"));
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

    @Test
    void efileReaderConfigShouldGenerateDataTagsFromSourceModelFields() {
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                mockDataSourceService(),
                mockDataModelService(),
                mock(EncryptionService.class),
                mockRuntimeOptionSchemaService());

        CollectionTaskDefinitionView definition = buildFileDefinition(31L, "id", "planDate");
        Map<String, Object> readerOptions = new LinkedHashMap<String, Object>();
        readerOptions.put("dataTag", Collections.singletonList("ignored"));
        definition.getSourceBindings().get(0).setReaderOptions(readerOptions);

        Map<String, Object> config = assemblerService.assemble(definition);

        @SuppressWarnings("unchecked")
        Map<String, Object> reader = (Map<String, Object>) config.get("reader");
        @SuppressWarnings("unchecked")
        Map<String, Object> readerConfig = (Map<String, Object>) reader.get("config");
        assertEquals("efile", readerConfig.get("fileType"));
        assertIterableEquals(Arrays.asList("planDate", "dataTime"), castList(readerConfig.get("dataTag")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) readerConfig.get("columns");
        assertEquals("id", columns.get(0).get("name"));
        assertEquals(Integer.valueOf(0), columns.get(0).get("index"));
        assertEquals("planDate", columns.get(1).get("name"));
        assertEquals(Integer.valueOf(2), columns.get(1).get("index"));
    }

    @Test
    void nonEfileFileReaderShouldRejectTagSourceFields() {
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                mockDataSourceService(),
                mockDataModelService(),
                mock(EncryptionService.class),
                mockRuntimeOptionSchemaService());

        assertThrows(StudioException.class, () -> assemblerService.assemble(buildFileDefinition(32L, "id", "planDate")));
    }

    @Test
    void fileWriterConfigShouldUseTargetModelOptionsAndDynamicWriterOptions() {
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                mockDataSourceService(),
                mockDataModelService(),
                mock(EncryptionService.class),
                mockRuntimeOptionSchemaService());

        CollectionTaskDefinitionView definition = new CollectionTaskDefinitionView();
        definition.setTaskType(CollectionTaskType.SINGLE_TABLE);
        CollectionTaskSourceBinding sourceBinding = new CollectionTaskSourceBinding();
        sourceBinding.setDatasourceId(1L);
        sourceBinding.setModelId(10L);
        sourceBinding.setSourceAlias("src1");
        definition.setSourceBindings(Collections.singletonList(sourceBinding));

        CollectionTaskTargetBinding targetBinding = new CollectionTaskTargetBinding();
        targetBinding.setDatasourceId(3L);
        targetBinding.setModelId(33L);
        Map<String, Object> writerOptions = new LinkedHashMap<String, Object>();
        writerOptions.put("writeMode", "overwrite");
        writerOptions.put("hasHeader", Boolean.FALSE);
        writerOptions.put("rootPath", "/runtime-ignored");
        targetBinding.setWriterOptions(writerOptions);
        definition.setTargetBinding(targetBinding);

        FieldMappingDefinition idMapping = new FieldMappingDefinition();
        idMapping.setSourceAlias("src1");
        idMapping.setSourceField("id");
        idMapping.setTargetField("id");
        FieldMappingDefinition tagMapping = new FieldMappingDefinition();
        tagMapping.setSourceAlias("src1");
        tagMapping.setSourceField("planDate");
        tagMapping.setTargetField("planDate");
        definition.setFieldMappings(Arrays.asList(idMapping, tagMapping));

        Map<String, Object> config = assemblerService.assemble(definition);

        @SuppressWarnings("unchecked")
        Map<String, Object> writer = (Map<String, Object>) config.get("writer");
        @SuppressWarnings("unchecked")
        Map<String, Object> writerConfig = (Map<String, Object>) writer.get("config");
        assertEquals("minio", writer.get("type"));
        assertEquals("/target-root", writerConfig.get("rootPath"));
        assertEquals("result.efile", writerConfig.get("fileName"));
        assertEquals("efile", writerConfig.get("fileType"));
        assertEquals("UTF-8", writerConfig.get("encoding"));
        assertEquals("|", writerConfig.get("delimiter"));
        assertEquals("overwrite", writerConfig.get("writeMode"));
        assertEquals(Boolean.FALSE, writerConfig.get("hasHeader"));
        assertFalse(writerConfig.containsKey("table"));

        @SuppressWarnings("unchecked")
        Map<String, Object> efile = (Map<String, Object>) writerConfig.get("efile");
        assertEquals("demo", efile.get("entity"));
        assertEquals("test", efile.get("type"));
        assertEquals("$getCurrentTime(yyyyMMdd_HH:mm:ss)", efile.get("dataTime"));
        assertEquals("T01", efile.get("tableName"));
        assertEquals("Demo", efile.get("tableCode"));
        assertEquals("$getCurrentTime(yyyyMMdd)", efile.get("planDate"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) writerConfig.get("columns");
        assertEquals("id", columns.get(0).get("name"));
        assertEquals(Integer.valueOf(0), columns.get(0).get("index"));
        assertEquals("LONG", columns.get(0).get("type"));
        assertEquals("planDate", columns.get(1).get("name"));
        assertEquals(Integer.valueOf(1), columns.get(1).get("index"));
        assertEquals("TAG", columns.get(1).get("sourceKind"));
    }

    @Test
    void httpReaderConfigShouldAssembleModelAndRuntimeOptionsWithoutIncremental() {
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                mockDataSourceService(),
                mockDataModelService(),
                testEncryptionService(),
                mockRuntimeOptionSchemaService());

        CollectionTaskDefinitionView definition = buildHttpDefinition(40L);
        CollectionTaskSourceBinding sourceBinding = definition.getSourceBindings().get(0);
        Map<String, Object> readerOptions = new LinkedHashMap<String, Object>();
        readerOptions.put("url", "http://evil.example.com/ignored");
        readerOptions.put("header", "{\"token\":\"{dyn_from_http_token(get,http://auth.example.com,,,msg)}\"}");
        readerOptions.put("params", "{\"pageNum\":\"{dyn_page}\",\"pageSize\":\"{dyn_pageSize}\"}");
        readerOptions.put("requestBody", "{\"active\":true}");
        readerOptions.put("pageRead", Boolean.TRUE);
        readerOptions.put("pageSize", Integer.valueOf(200));
        sourceBinding.setReaderOptions(readerOptions);
        CollectionIncrementalDefinition incremental = new CollectionIncrementalDefinition();
        incremental.setEnabled(Boolean.TRUE);
        incremental.setIncrColumn("id");
        incremental.setPkValue(Long.valueOf(99L));
        sourceBinding.setIncremental(incremental);
        Map<String, Object> executionOptions = new LinkedHashMap<String, Object>();
        executionOptions.put("collectionMode", "INCREMENTAL");
        definition.setExecutionOptions(executionOptions);

        Map<String, Object> config = assemblerService.assemble(definition);

        @SuppressWarnings("unchecked")
        Map<String, Object> reader = (Map<String, Object>) config.get("reader");
        @SuppressWarnings("unchecked")
        Map<String, Object> readerConfig = (Map<String, Object>) reader.get("config");
        assertEquals("http", reader.get("type"));
        assertEquals("http://api.example.com/base/users", readerConfig.get("url"));
        assertEquals("GET", readerConfig.get("mode"));
        assertEquals("json", readerConfig.get("resultType"));
        assertEquals("data.total", readerConfig.get("totalCodePath"));
        assertEquals(Boolean.TRUE, readerConfig.get("pageRead"));
        assertEquals(Integer.valueOf(200), readerConfig.get("pageSize"));
        assertEquals("{\"token\":\"{dyn_from_http_token(get,http://auth.example.com,,,msg)}\"}", readerConfig.get("header"));
        assertEquals("{\"pageNum\":\"{dyn_page}\",\"pageSize\":\"{dyn_pageSize}\"}", readerConfig.get("params"));
        assertEquals("{\"active\":true}", readerConfig.get("requestBody"));
        assertFalse(readerConfig.containsKey("incrColumn"));
        assertFalse(readerConfig.containsKey("pkValue"));

        @SuppressWarnings("unchecked")
        Map<String, Object> responseStatus = (Map<String, Object>) readerConfig.get("responseStatus");
        assertEquals("code", responseStatus.get("path"));
        assertEquals("200", responseStatus.get("code"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) readerConfig.get("columns");
        assertEquals(1, columns.size());
        assertEquals("data.items", columns.get(0).get("parentNode"));
        assertEquals("id", columns.get(0).get("name"));
        assertEquals("STRING", columns.get(0).get("type"));
    }

    @Test
    void httpReaderConfigShouldMergeModelReaderOptionsBeforeTaskOptions() {
        DataModelService dataModelService = mockDataModelService();
        DataModelDefinition httpModel = buildHttpModel(false);
        Map<String, Object> modelReaderOptions = new LinkedHashMap<String, Object>();
        modelReaderOptions.put("url", "http://evil.example.com/model");
        modelReaderOptions.put("mode", "POST");
        modelReaderOptions.put("header", "{\"X-Model\":\"model\"}");
        modelReaderOptions.put("params", "{\"page\":\"{dyn_page}\"}");
        modelReaderOptions.put("pageRead", Boolean.TRUE);
        modelReaderOptions.put("pageSize", Integer.valueOf(100));
        httpModel.getTechnicalMetadata().put("readerOptions", modelReaderOptions);
        when(dataModelService.get(40L)).thenReturn(httpModel);
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                mockDataSourceService(),
                dataModelService,
                mock(EncryptionService.class),
                mockRuntimeOptionSchemaService());

        CollectionTaskDefinitionView definition = buildHttpDefinition(40L);
        Map<String, Object> taskReaderOptions = new LinkedHashMap<String, Object>();
        taskReaderOptions.put("header", "{\"X-Task\":\"task\"}");
        taskReaderOptions.put("pageSize", Integer.valueOf(50));
        definition.getSourceBindings().get(0).setReaderOptions(taskReaderOptions);

        Map<String, Object> config = assemblerService.assemble(definition);

        @SuppressWarnings("unchecked")
        Map<String, Object> reader = (Map<String, Object>) config.get("reader");
        @SuppressWarnings("unchecked")
        Map<String, Object> readerConfig = (Map<String, Object>) reader.get("config");
        assertEquals("http://api.example.com/base/users", readerConfig.get("url"));
        assertEquals("GET", readerConfig.get("mode"));
        assertEquals("{\"X-Task\":\"task\",\"X-Model\":\"model\"}", readerConfig.get("header"));
        assertEquals("{\"page\":\"{dyn_page}\"}", readerConfig.get("params"));
        assertEquals(Boolean.TRUE, readerConfig.get("pageRead"));
        assertEquals(Integer.valueOf(50), readerConfig.get("pageSize"));
    }

    @Test
    void httpReaderConfigShouldPreferRequestPathAndSupportLegacyEndpointBaseUrl() {
        DataSourceService dataSourceService = mockDataSourceService();
        DataSourceDefinition httpDatasource = new DataSourceDefinition();
        httpDatasource.setId(4L);
        httpDatasource.setTypeCode("http");
        Map<String, Object> datasourceMetadata = new LinkedHashMap<String, Object>();
        datasourceMetadata.put("endpoint", "http://api.example.com/base");
        httpDatasource.setTechnicalMetadata(datasourceMetadata);
        when(dataSourceService.getInternal(4L)).thenReturn(httpDatasource);

        DataModelService dataModelService = mockDataModelService();
        DataModelDefinition httpModel = buildHttpModel(false);
        httpModel.setPhysicalLocator(null);
        httpModel.getTechnicalMetadata().put("requestPath", "/request-path");
        httpModel.getTechnicalMetadata().put("physicalName", "/legacy-physical-name");
        when(dataModelService.get(47L)).thenReturn(httpModel);

        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                dataSourceService,
                dataModelService,
                mock(EncryptionService.class),
                mockRuntimeOptionSchemaService());

        Map<String, Object> config = assemblerService.assemble(buildHttpDefinition(47L));

        @SuppressWarnings("unchecked")
        Map<String, Object> reader = (Map<String, Object>) config.get("reader");
        @SuppressWarnings("unchecked")
        Map<String, Object> readerConfig = (Map<String, Object>) reader.get("config");
        assertEquals("http://api.example.com/base/request-path", readerConfig.get("url"));
    }

    @Test
    void httpPreviewShouldMaskModelAndTaskCredentialsWhileExecutionKeepsPlainValues() {
        DataModelService dataModelService = mockDataModelService();
        DataModelDefinition httpModel = buildHttpModel(false);
        Map<String, Object> modelReaderOptions = new LinkedHashMap<String, Object>();
        modelReaderOptions.put("header", "{\"Authorization\":\"Bearer model-secret\"}");
        modelReaderOptions.put("params", "{\"api_token\":\"model-query-secret\"}");
        modelReaderOptions.put("requestBody", "{\"password\":\"model-body-secret\"}");
        httpModel.getTechnicalMetadata().put("readerOptions", modelReaderOptions);
        when(dataModelService.get(40L)).thenReturn(httpModel);
        EncryptionService encryptionService = testEncryptionService();
        HttpReaderOptionSecurityService securityService = new HttpReaderOptionSecurityService(encryptionService);
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                mockDataSourceService(), dataModelService, encryptionService, mockRuntimeOptionSchemaService());

        CollectionTaskDefinitionView definition = buildHttpDefinition(40L);
        Map<String, Object> submittedTaskOptions = new LinkedHashMap<String, Object>();
        submittedTaskOptions.put("header", "{\"Authorization\":\"Bearer task-secret\"}");
        submittedTaskOptions.put("params", "{\"api_token\":\"task-query-secret\"}");
        submittedTaskOptions.put("requestBody", "{\"password\":\"task-body-secret\"}");
        definition.getSourceBindings().get(0).setReaderOptions(
                securityService.prepareReaderOptionOverrides(submittedTaskOptions, httpModel.getTechnicalMetadata()));

        Map<String, Object> executionConfig = castMap(
                castMap(assemblerService.assemble(definition).get("reader")).get("config"));
        Map<String, Object> previewConfig = castMap(
                castMap(assemblerService.assemblePreview(definition).get("reader")).get("config"));

        assertTrue(String.valueOf(executionConfig.get("header")).contains("task-secret"));
        assertTrue(String.valueOf(executionConfig.get("params")).contains("task-query-secret"));
        assertTrue(String.valueOf(executionConfig.get("requestBody")).contains("task-body-secret"));
        assertFalse(String.valueOf(previewConfig).contains("task-secret"));
        assertFalse(String.valueOf(previewConfig).contains("task-query-secret"));
        assertFalse(String.valueOf(previewConfig).contains("task-body-secret"));
        assertTrue(String.valueOf(previewConfig.get("header")).contains("****"));
        assertTrue(String.valueOf(previewConfig.get("params")).contains("****"));
        assertTrue(String.valueOf(previewConfig.get("requestBody")).contains("****"));
    }

    @Test
    void httpReaderConfigShouldDeriveRestXmlRuntimeDefaultsFromProtocol() {
        DataModelService dataModelService = mockDataModelService();
        DataModelDefinition httpModel = buildHttpModel(false);
        httpModel.getTechnicalMetadata().put("protocolMode", "REST_XML");
        httpModel.getTechnicalMetadata().put("resultType", "json");
        Map<String, Object> staleReaderOptions = new LinkedHashMap<String, Object>();
        staleReaderOptions.put("contentType", "application/json;charset=utf-8");
        httpModel.getTechnicalMetadata().put("readerOptions", staleReaderOptions);
        when(dataModelService.get(40L)).thenReturn(httpModel);
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                mockDataSourceService(),
                dataModelService,
                mock(EncryptionService.class),
                mockRuntimeOptionSchemaService());

        Map<String, Object> config = assemblerService.assemble(buildHttpDefinition(40L));

        Map<String, Object> readerConfig = castMap(castMap(config.get("reader")).get("config"));
        assertEquals("REST_XML", readerConfig.get("protocolMode"));
        assertEquals("xml", readerConfig.get("resultType"));
        assertEquals("application/xml;charset=UTF-8", readerConfig.get("contentType"));
    }

    @Test
    void httpReaderConfigShouldIgnoreUnmodifiedInheritedMaskedOptionsButKeepExplicitOverrides() {
        DataModelService dataModelService = mockDataModelService();
        DataModelDefinition httpModel = buildHttpModel(false);
        Map<String, Object> modelReaderOptions = new LinkedHashMap<String, Object>();
        String inheritedHeader = "{\"Authorization\":\"Bearer model-secret\",\"X-Model\":\"model\"}";
        modelReaderOptions.put("header", inheritedHeader);
        httpModel.getTechnicalMetadata().put("readerOptions", modelReaderOptions);
        when(dataModelService.get(40L)).thenReturn(httpModel);

        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setEncryptionSecret("collection-task-http-inheritance-test");
        EncryptionService encryptionService = new EncryptionService(properties);
        HttpReaderOptionSecurityService securityService = new HttpReaderOptionSecurityService(encryptionService);
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                mockDataSourceService(),
                dataModelService,
                encryptionService,
                mockRuntimeOptionSchemaService());

        CollectionTaskDefinitionView definition = buildHttpDefinition(40L);
        Map<String, Object> maskedMetadata = securityService.maskTechnicalMetadata(httpModel.getTechnicalMetadata());
        Map<String, Object> maskedReaderOptions = castMap(maskedMetadata.get("readerOptions"));
        Map<String, Object> taskReaderOptions = new LinkedHashMap<String, Object>();
        taskReaderOptions.put("header", maskedReaderOptions.get("header"));
        taskReaderOptions = securityService.prepareReaderOptionOverrides(
                taskReaderOptions, httpModel.getTechnicalMetadata());
        definition.getSourceBindings().get(0).setReaderOptions(taskReaderOptions);

        Map<String, Object> inheritedConfig = castMap(castMap(assemblerService.assemble(definition).get("reader")).get("config"));
        assertEquals(inheritedHeader, inheritedConfig.get("header"));

        String siblingChangedHeader = String.valueOf(maskedReaderOptions.get("header"))
                .replace("\"model\"", "\"task\"");
        taskReaderOptions.put("header", siblingChangedHeader);
        taskReaderOptions = securityService.prepareReaderOptionOverrides(
                taskReaderOptions, httpModel.getTechnicalMetadata());
        definition.getSourceBindings().get(0).setReaderOptions(taskReaderOptions);
        Map<String, Object> siblingChangedConfig = castMap(
                castMap(assemblerService.assemble(definition).get("reader")).get("config"));
        assertEquals(Map.of("Authorization", "Bearer model-secret", "X-Model", "task"),
                com.alibaba.fastjson.JSONObject.parseObject(String.valueOf(siblingChangedConfig.get("header")), Map.class));

        String explicitHeader = "{\"Authorization\":\"Bearer task-secret\",\"X-Model\":\"task\"}";
        taskReaderOptions.put("header", explicitHeader);
        taskReaderOptions = securityService.prepareReaderOptionOverrides(
                taskReaderOptions, httpModel.getTechnicalMetadata());
        definition.getSourceBindings().get(0).setReaderOptions(taskReaderOptions);
        Map<String, Object> overriddenConfig = castMap(castMap(assemblerService.assemble(definition).get("reader")).get("config"));
        assertEquals(explicitHeader, overriddenConfig.get("header"));

        String removedHeader = "{\"Authorization\":\""
                + HttpReaderOptionSecurityService.REMOVED_VALUE_MARKER + "\",\"X-Model\":\"task\"}";
        taskReaderOptions.put("header", removedHeader);
        taskReaderOptions = securityService.prepareReaderOptionOverrides(
                taskReaderOptions, httpModel.getTechnicalMetadata());
        definition.getSourceBindings().get(0).setReaderOptions(taskReaderOptions);
        Map<String, Object> removedConfig = castMap(castMap(assemblerService.assemble(definition).get("reader")).get("config"));
        assertEquals(Map.of("X-Model", "task"),
                com.alibaba.fastjson.JSONObject.parseObject(String.valueOf(removedConfig.get("header")), Map.class));
    }

    @Test
    void httpReaderConfigShouldRejectPartialBusinessStatusAndInvalidJsonObjectOptions() {
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                mockDataSourceService(),
                mockDataModelService(),
                mock(EncryptionService.class),
                mockRuntimeOptionSchemaService());

        assertThrows(StudioException.class, () -> assemblerService.assemble(buildHttpDefinition(41L)));

        CollectionTaskDefinitionView definition = buildHttpDefinition(40L);
        Map<String, Object> readerOptions = new LinkedHashMap<String, Object>();
        readerOptions.put("header", "[]");
        definition.getSourceBindings().get(0).setReaderOptions(readerOptions);

        assertThrows(StudioException.class, () -> assemblerService.assemble(definition));
    }

    @Test
    void httpWriterConfigShouldAssembleModelRuntimeOptionsAndPreserveJsonStrings() {
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                mockDataSourceService(),
                mockDataModelService(),
                mock(EncryptionService.class),
                mockRuntimeOptionSchemaService());

        CollectionTaskDefinitionView definition = buildHttpWriterDefinition();
        Map<String, Object> writerOptions = new LinkedHashMap<String, Object>();
        writerOptions.put("url", "http://evil.example.com/ignored");
        writerOptions.put("mode", "DELETE");
        writerOptions.put("header", "{\"Authorization\":\"Bearer {dyn_from_http_token(GET,http://auth.example.com,,,data.token)}\"}");
        writerOptions.put("params", "{\"page\":\"{dyn_page}\"}");
        writerOptions.put("requestBody", "{\"meta\":{\"source\":\"studio\"}}");
        writerOptions.put("payloadMode", "array");
        writerOptions.put("dataNodePath", "data.items");
        writerOptions.put("includeTotal", Boolean.TRUE);
        writerOptions.put("totalNodePath", "data.total");
        writerOptions.put("batchSize", Integer.valueOf(200));
        writerOptions.put("responseStatus.path", "code");
        writerOptions.put("responseStatus.code", "0");
        definition.getTargetBinding().setWriterOptions(writerOptions);

        Map<String, Object> config = assemblerService.assemble(definition);

        @SuppressWarnings("unchecked")
        Map<String, Object> writer = (Map<String, Object>) config.get("writer");
        @SuppressWarnings("unchecked")
        Map<String, Object> writerConfig = (Map<String, Object>) writer.get("config");
        assertEquals("http", writer.get("type"));
        assertEquals("http://api.example.com/base/orders", writerConfig.get("url"));
        assertEquals("POST", writerConfig.get("mode"));
        assertEquals("{\"Authorization\":\"Bearer {dyn_from_http_token(GET,http://auth.example.com,,,data.token)}\"}", writerConfig.get("header"));
        assertEquals("{\"page\":\"{dyn_page}\"}", writerConfig.get("params"));
        assertEquals("{\"meta\":{\"source\":\"studio\"}}", writerConfig.get("requestBody"));
        assertEquals("array", writerConfig.get("payloadMode"));
        assertEquals("data.items", writerConfig.get("dataNodePath"));
        assertEquals(Boolean.TRUE, writerConfig.get("includeTotal"));
        assertEquals("data.total", writerConfig.get("totalNodePath"));
        assertEquals(Integer.valueOf(200), writerConfig.get("batchSize"));
        assertFalse(writerConfig.containsKey("connect"));
        assertFalse(writerConfig.containsKey("table"));

        @SuppressWarnings("unchecked")
        Map<String, Object> responseStatus = (Map<String, Object>) writerConfig.get("responseStatus");
        assertEquals("code", responseStatus.get("path"));
        assertEquals("0", responseStatus.get("code"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) writerConfig.get("columns");
        assertEquals(2, columns.size());
        assertEquals(Integer.valueOf(0), columns.get(0).get("index"));
        assertEquals("id", columns.get(0).get("name"));
        assertEquals("LONG", columns.get(0).get("type"));
        assertEquals(Integer.valueOf(1), columns.get(1).get("index"));
        assertEquals("name", columns.get(1).get("name"));
        assertEquals("TEXT", columns.get(1).get("type"));
    }

    @Test
    void httpSoapReaderConfigShouldUseSoapProfileAndPreserveEnvelope() {
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                mockDataSourceService(),
                mockDataModelService(),
                mock(EncryptionService.class),
                mockRuntimeOptionSchemaService());

        CollectionTaskDefinitionView definition = buildHttpDefinition(43L);
        Map<String, Object> readerOptions = new LinkedHashMap<String, Object>();
        readerOptions.put("header", "{\"X-Trace\":\"test\"}");
        readerOptions.put("requestBody", "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:tns=\"urn:studio\"><soap:Body><tns:QueryRows/></soap:Body></soap:Envelope>");
        definition.getSourceBindings().get(0).setReaderOptions(readerOptions);

        Map<String, Object> config = assemblerService.assemble(definition);

        @SuppressWarnings("unchecked")
        Map<String, Object> reader = (Map<String, Object>) config.get("reader");
        @SuppressWarnings("unchecked")
        Map<String, Object> readerConfig = (Map<String, Object>) reader.get("config");
        assertEquals("http", reader.get("type"));
        assertEquals("http://api.example.com/base/services/source/ws", readerConfig.get("url"));
        assertEquals("POST", readerConfig.get("mode"));
        assertEquals("SOAP", readerConfig.get("protocolMode"));
        assertEquals("SOAP_11", readerConfig.get("soapVersion"));
        assertEquals("soap", readerConfig.get("resultType"));
        assertEquals("text/xml;charset=UTF-8", readerConfig.get("contentType"));
        assertTrue(String.valueOf(readerConfig.get("header")).contains("SOAPAction"));
        assertTrue(String.valueOf(readerConfig.get("header")).contains("urn:studio/QueryRows"));
        assertTrue(String.valueOf(readerConfig.get("requestBody")).contains("QueryRows"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) readerConfig.get("columns");
        assertEquals("QueryRowsResponse.items", columns.get(0).get("parentNode"));
        assertEquals("id", columns.get(0).get("name"));
    }

    @Test
    void httpSoapWriterConfigShouldUseSoapTemplateAndSupportArrayPayload() {
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                mockDataSourceService(),
                mockDataModelService(),
                mock(EncryptionService.class),
                mockRuntimeOptionSchemaService());

        CollectionTaskDefinitionView definition = buildHttpWriterDefinition();
        definition.getTargetBinding().setModelId(44L);
        Map<String, Object> writerOptions = new LinkedHashMap<String, Object>();
        writerOptions.put("requestBody", "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:tns=\"urn:studio\"><soap:Body><tns:WriteRow><tns:id>{{id}}</tns:id><tns:name>{{name}}</tns:name></tns:WriteRow></soap:Body></soap:Envelope>");
        writerOptions.put("responseStatus.path", "WriteRowResponse.code");
        writerOptions.put("responseStatus.code", "200");
        definition.getTargetBinding().setWriterOptions(writerOptions);

        Map<String, Object> config = assemblerService.assemble(definition);

        @SuppressWarnings("unchecked")
        Map<String, Object> writer = (Map<String, Object>) config.get("writer");
        @SuppressWarnings("unchecked")
        Map<String, Object> writerConfig = (Map<String, Object>) writer.get("config");
        assertEquals("http", writer.get("type"));
        assertEquals("http://api.example.com/base/ingestion/target/ws", writerConfig.get("url"));
        assertEquals("POST", writerConfig.get("mode"));
        assertEquals("SOAP", writerConfig.get("protocolMode"));
        assertEquals("SOAP_12", writerConfig.get("soapVersion"));
        assertEquals("soap", writerConfig.get("payloadFormat"));
        assertEquals("soap", writerConfig.get("responseType"));
        assertEquals("application/soap+xml;charset=UTF-8", writerConfig.get("contentType"));
        assertEquals("object", writerConfig.get("payloadMode"));
        assertTrue(String.valueOf(writerConfig.get("header")).contains("SOAPAction"));
        assertTrue(String.valueOf(writerConfig.get("requestBody")).contains("{{id}}"));
        assertEquals(Integer.valueOf(500), writerConfig.get("batchSize"));

        @SuppressWarnings("unchecked")
        Map<String, Object> responseStatus = (Map<String, Object>) writerConfig.get("responseStatus");
        assertEquals("WriteRowResponse.code", responseStatus.get("path"));
        assertEquals("200", responseStatus.get("code"));

        writerOptions.put("payloadMode", "array");
        writerOptions.put("batchSize", Integer.valueOf(2));
        writerOptions.put("dataNodePath", "payload.items.item");
        writerOptions.put("requestBody", "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:tns=\"urn:studio\"><soap:Body><tns:WriteRows><tns:records>{{#records}}<tns:record><tns:id>{{id}}</tns:id><tns:name>{{name}}</tns:name></tns:record>{{/records}}</tns:records></tns:WriteRows></soap:Body></soap:Envelope>");

        Map<String, Object> arrayConfig = assemblerService.assemble(definition);
        @SuppressWarnings("unchecked")
        Map<String, Object> arrayWriter = (Map<String, Object>) arrayConfig.get("writer");
        @SuppressWarnings("unchecked")
        Map<String, Object> arrayWriterConfig = (Map<String, Object>) arrayWriter.get("config");
        assertEquals("array", arrayWriterConfig.get("payloadMode"));
        assertEquals(Integer.valueOf(2), arrayWriterConfig.get("batchSize"));
        assertEquals("payload.items.item", arrayWriterConfig.get("dataNodePath"));
        assertTrue(String.valueOf(arrayWriterConfig.get("requestBody")).contains("{{#records}}"));
        assertTrue(String.valueOf(arrayWriterConfig.get("requestBody")).contains("{{/records}}"));

        writerOptions.put("requestBody", "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:tns=\"urn:studio\"><soap:Body><tns:WriteRow><tns:id>{{id}}</tns:id></tns:WriteRow></soap:Body></soap:Envelope>");
        Map<String, Object> regeneratedConfig = assemblerService.assemble(definition);
        @SuppressWarnings("unchecked")
        Map<String, Object> regeneratedWriter = (Map<String, Object>) regeneratedConfig.get("writer");
        @SuppressWarnings("unchecked")
        Map<String, Object> regeneratedWriterConfig = (Map<String, Object>) regeneratedWriter.get("config");
        assertTrue(String.valueOf(regeneratedWriterConfig.get("requestBody")).contains("{{#records}}"));
        assertTrue(String.valueOf(regeneratedWriterConfig.get("requestBody")).contains("{{name}}"));
    }

    @Test
    void httpSoapWriterArrayShouldInferDataNodePathFromTargetParentNode() {
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                mockDataSourceService(),
                mockDataModelService(),
                mock(EncryptionService.class),
                mockRuntimeOptionSchemaService());

        CollectionTaskDefinitionView definition = buildHttpWriterDefinition();
        definition.getTargetBinding().setModelId(45L);
        Map<String, Object> writerOptions = new LinkedHashMap<String, Object>();
        writerOptions.put("payloadMode", "array");
        writerOptions.put("batchSize", Integer.valueOf(2));
        writerOptions.put("requestBody", "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:tns=\"urn:studio\"><soap:Body><tns:WriteRows>{{#records}}<tns:record><tns:id>{{id}}</tns:id><tns:name>{{name}}</tns:name></tns:record>{{/records}}</tns:WriteRows></soap:Body></soap:Envelope>");
        definition.getTargetBinding().setWriterOptions(writerOptions);

        Map<String, Object> config = assemblerService.assemble(definition);

        @SuppressWarnings("unchecked")
        Map<String, Object> writer = (Map<String, Object>) config.get("writer");
        @SuppressWarnings("unchecked")
        Map<String, Object> writerConfig = (Map<String, Object>) writer.get("config");
        assertEquals("record", writerConfig.get("dataNodePath"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) writerConfig.get("columns");
        assertEquals("record", columns.get(0).get("parentNode"));
    }

    @Test
    void httpSoapWriterBodyShouldBeGeneratedFromEffectiveFieldMappings() {
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                mockDataSourceService(),
                mockDataModelService(),
                mock(EncryptionService.class),
                mockRuntimeOptionSchemaService());

        CollectionTaskDefinitionView definition = buildHttpWriterDefinition();
        definition.getTargetBinding().setModelId(44L);
        FieldMappingDefinition idMapping = new FieldMappingDefinition();
        idMapping.setSourceAlias("src1");
        idMapping.setSourceField("source_col");
        idMapping.setTargetField("id");
        definition.setFieldMappings(Collections.singletonList(idMapping));

        Map<String, Object> writerOptions = new LinkedHashMap<String, Object>();
        writerOptions.put("requestBody", "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:tns=\"urn:studio\"><soap:Body><tns:WriteRow><tns:id>{{id}}</tns:id><tns:name>{{name}}</tns:name></tns:WriteRow></soap:Body></soap:Envelope>");
        definition.getTargetBinding().setWriterOptions(writerOptions);

        Map<String, Object> config = assemblerService.assemble(definition);

        @SuppressWarnings("unchecked")
        Map<String, Object> writer = (Map<String, Object>) config.get("writer");
        @SuppressWarnings("unchecked")
        Map<String, Object> writerConfig = (Map<String, Object>) writer.get("config");
        String requestBody = String.valueOf(writerConfig.get("requestBody"));
        assertTrue(requestBody.contains("{{id}}"));
        assertFalse(requestBody.contains("{{name}}"));
    }

    @Test
    void httpSoapWriterArrayShouldRejectMissingOrIncompatibleDataNodePath() {
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                mockDataSourceService(),
                mockDataModelService(),
                mock(EncryptionService.class),
                mockRuntimeOptionSchemaService());

        CollectionTaskDefinitionView noParentDefinition = buildHttpWriterDefinition();
        noParentDefinition.getTargetBinding().setModelId(44L);
        Map<String, Object> writerOptions = new LinkedHashMap<String, Object>();
        writerOptions.put("payloadMode", "array");
        writerOptions.put("requestBody", "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:tns=\"urn:studio\"><soap:Body><tns:WriteRows>{{#records}}<tns:record><tns:id>{{id}}</tns:id></tns:record>{{/records}}</tns:WriteRows></soap:Body></soap:Envelope>");
        noParentDefinition.getTargetBinding().setWriterOptions(writerOptions);
        assertThrows(StudioException.class, () -> assemblerService.assemble(noParentDefinition));

        CollectionTaskDefinitionView incompatibleDefinition = buildHttpWriterDefinition();
        incompatibleDefinition.getTargetBinding().setModelId(46L);
        Map<String, Object> incompatibleOptions = new LinkedHashMap<String, Object>();
        incompatibleOptions.put("payloadMode", "array");
        incompatibleOptions.put("dataNodePath", "record");
        incompatibleOptions.put("requestBody", "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:tns=\"urn:studio\"><soap:Body><tns:WriteRows>{{#records}}<tns:record><tns:id>{{id}}</tns:id></tns:record>{{/records}}</tns:WriteRows></soap:Body></soap:Envelope>");
        incompatibleDefinition.getTargetBinding().setWriterOptions(incompatibleOptions);
        assertThrows(StudioException.class, () -> assemblerService.assemble(incompatibleDefinition));
    }

    private DataModelDefinition buildModelWithColumns(Long id, String physicalLocator, String... fieldNames) {
        DataModelDefinition model = new DataModelDefinition();
        model.setId(id);
        model.setPhysicalLocator(physicalLocator);
        Map<String, Object> technicalMetadata = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> columns = new ArrayList<Map<String, Object>>();
        for (String fieldName : fieldNames) {
            columns.add(column(fieldName));
        }
        technicalMetadata.put("columns", columns);
        model.setTechnicalMetadata(technicalMetadata);
        return model;
    }

}
