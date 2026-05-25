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
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.PluginRuntimeOptionSchemaService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

abstract class CollectionTaskAssemblerTestSupport {
    protected DataSourceService mockDataSourceService() {
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
        DataSourceDefinition httpDatasource = new DataSourceDefinition();
        httpDatasource.setId(4L);
        httpDatasource.setTypeCode("http");
        Map<String, Object> httpMetadata = new LinkedHashMap<String, Object>();
        httpMetadata.put("url", "http://api.example.com/base");
        httpDatasource.setTechnicalMetadata(httpMetadata);
        when(service.getInternal(1L)).thenReturn(datasource);
        when(service.getInternal(2L)).thenReturn(datasource);
        when(service.getInternal(3L)).thenReturn(minioDatasource);
        when(service.getInternal(4L)).thenReturn(httpDatasource);
        return service;
    }

    protected PluginRuntimeOptionSchemaService mockRuntimeOptionSchemaService() {
        PluginRuntimeOptionSchemaService service = mock(PluginRuntimeOptionSchemaService.class);
        when(service.resolvePluginType("mysql8", "reader")).thenReturn("mysql8");
        when(service.resolvePluginType("mysql8", "writer")).thenReturn("mysql8");
        when(service.resolvePluginType("minio", "reader")).thenReturn("minio");
        when(service.resolvePluginType("minio", "writer")).thenReturn("minio");
        when(service.resolvePluginType("http", "reader")).thenReturn("http");
        when(service.resolvePluginType("http", "writer")).thenReturn("http");
        when(service.sourceCategory("mysql8")).thenReturn("DATABASE");
        when(service.sourceCategory("minio")).thenReturn("FILE_SYSTEM");
        when(service.sourceCategory("http")).thenReturn("HTTP_API");
        when(service.reservedKeys("reader")).thenReturn(Arrays.asList("connect", "config", "table", "topic",
                "measurement", "columns", "sourceAlias", "sources", "join", "fieldMappings", "incrColumn", "incrModel", "pkValue", "dataTag",
                "rootPath", "partitionType", "partition", "pattern", "fileType", "encoding", "delimiter",
                "url", "mode", "resultType", "responseStatus", "totalCodePath"));
        when(service.reservedKeys("writer")).thenReturn(Arrays.asList("connect", "table", "topic", "measurement",
                "columns", "sourceAlias", "rootPath", "fileName", "fileType", "encoding", "delimiter", "efile",
                "url", "mode"));
        return service;
    }

    protected DataModelService mockDataModelService() {
        DataModelService service = mock(DataModelService.class);
        DataModelDefinition sourceModel = buildModel(10L, "source_table");
        DataModelDefinition sourceModel2 = buildModel(11L, "source_table_2");
        DataModelDefinition targetModel = buildModel(20L, "target_table");
        DataModelDefinition fileModel = buildFileModel();
        DataModelDefinition efileModel = buildEFileModel("efile");
        DataModelDefinition invalidTagFileModel = buildEFileModel("csv");
        DataModelDefinition fileWriterModel = buildFileWriterModel();
        DataModelDefinition httpModel = buildHttpModel(false);
        DataModelDefinition invalidHttpModel = buildHttpModel(true);
        DataModelDefinition httpWriterModel = buildHttpWriterModel();
        when(service.get(10L)).thenReturn(sourceModel);
        when(service.get(11L)).thenReturn(sourceModel2);
        when(service.get(20L)).thenReturn(targetModel);
        when(service.get(30L)).thenReturn(fileModel);
        when(service.get(31L)).thenReturn(efileModel);
        when(service.get(32L)).thenReturn(invalidTagFileModel);
        when(service.get(33L)).thenReturn(fileWriterModel);
        when(service.get(40L)).thenReturn(httpModel);
        when(service.get(41L)).thenReturn(invalidHttpModel);
        when(service.get(42L)).thenReturn(httpWriterModel);
        return service;
    }

    protected DataModelDefinition buildModel(Long id, String physicalLocator) {
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

    protected Map<String, Object> column(String name) {
        Map<String, Object> column = new LinkedHashMap<String, Object>();
        column.put("name", name);
        return column;
    }

    protected DataModelDefinition buildFileModel() {
        DataModelDefinition model = new DataModelDefinition();
        model.setId(30L);
        model.setPhysicalLocator("/model-root");
        Map<String, Object> technicalMetadata = new LinkedHashMap<String, Object>();
        technicalMetadata.put("rootPath", "/model-root");
        technicalMetadata.put("partitionType", "glob");
        technicalMetadata.put("partition", "*.csv");
        technicalMetadata.put("fileType", "csv");
        technicalMetadata.put("encoding", "UTF-8");
        technicalMetadata.put("delimiter", ",");
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

    protected DataModelDefinition buildHttpModel(boolean partialBusinessStatus) {
        DataModelDefinition model = new DataModelDefinition();
        model.setId(partialBusinessStatus ? 41L : 40L);
        model.setPhysicalLocator("/users");
        Map<String, Object> technicalMetadata = new LinkedHashMap<String, Object>();
        technicalMetadata.put("mode", "GET");
        technicalMetadata.put("resultType", "json");
        technicalMetadata.put("businessStatusPath", "code");
        if (!partialBusinessStatus) {
            technicalMetadata.put("businessStatusCode", "200");
        }
        technicalMetadata.put("totalCodePath", "data.total");
        List<Map<String, Object>> columns = new ArrayList<Map<String, Object>>();
        Map<String, Object> id = column("id");
        id.put("parentNode", "data.items");
        columns.add(id);
        Map<String, Object> name = column("name");
        name.put("parentNode", "data.items");
        name.put("type", "TEXT");
        columns.add(name);
        technicalMetadata.put("columns", columns);
        model.setTechnicalMetadata(technicalMetadata);
        return model;
    }

    protected DataModelDefinition buildHttpWriterModel() {
        DataModelDefinition model = new DataModelDefinition();
        model.setId(42L);
        model.setPhysicalLocator("/orders");
        Map<String, Object> technicalMetadata = new LinkedHashMap<String, Object>();
        technicalMetadata.put("mode", "POST");
        List<Map<String, Object>> columns = new ArrayList<Map<String, Object>>();
        Map<String, Object> id = column("id");
        id.put("type", "LONG");
        columns.add(id);
        Map<String, Object> name = column("name");
        name.put("type", "TEXT");
        columns.add(name);
        technicalMetadata.put("columns", columns);
        model.setTechnicalMetadata(technicalMetadata);
        return model;
    }

    protected DataModelDefinition buildEFileModel(String fileType) {
        DataModelDefinition model = new DataModelDefinition();
        model.setId("efile".equals(fileType) ? 31L : 32L);
        model.setPhysicalLocator("/efile-root");
        Map<String, Object> technicalMetadata = new LinkedHashMap<String, Object>();
        technicalMetadata.put("rootPath", "/efile-root");
        technicalMetadata.put("partitionType", "glob");
        technicalMetadata.put("partition", "*.efile");
        technicalMetadata.put("fileType", fileType);
        technicalMetadata.put("encoding", "UTF-8");
        List<Map<String, Object>> columns = new ArrayList<Map<String, Object>>();
        Map<String, Object> id = column("id");
        id.put("index", Integer.valueOf(0));
        id.put("type", "LONG");
        columns.add(id);
        Map<String, Object> name = column("name");
        name.put("index", Integer.valueOf(1));
        columns.add(name);
        Map<String, Object> planDate = column("planDate");
        planDate.put("sourceKind", "TAG");
        columns.add(planDate);
        Map<String, Object> dataTime = column("dataTime");
        dataTime.put("sourceKind", "TAG");
        columns.add(dataTime);
        technicalMetadata.put("columns", columns);
        model.setTechnicalMetadata(technicalMetadata);
        return model;
    }

    protected DataModelDefinition buildFileWriterModel() {
        DataModelDefinition model = new DataModelDefinition();
        model.setId(33L);
        model.setPhysicalLocator("result.efile");
        Map<String, Object> technicalMetadata = new LinkedHashMap<String, Object>();
        technicalMetadata.put("rootPath", "/target-root");
        technicalMetadata.put("fileName", "result.efile");
        technicalMetadata.put("fileType", "efile");
        technicalMetadata.put("encoding", "UTF-8");
        technicalMetadata.put("delimiter", "|");
        technicalMetadata.put("efile.entity", "demo");
        technicalMetadata.put("efile.type", "test");
        technicalMetadata.put("efile.dataTime", "$getCurrentTime(yyyyMMdd_HH:mm:ss)");
        technicalMetadata.put("efile.tableName", "T01");
        technicalMetadata.put("efile.tableCode", "Demo");
        technicalMetadata.put("efile.planDate", "$getCurrentTime(yyyyMMdd)");
        List<Map<String, Object>> columns = new ArrayList<Map<String, Object>>();
        Map<String, Object> id = column("id");
        id.put("type", "LONG");
        columns.add(id);
        Map<String, Object> planDate = column("planDate");
        planDate.put("sourceKind", "TAG");
        columns.add(planDate);
        technicalMetadata.put("columns", columns);
        model.setTechnicalMetadata(technicalMetadata);
        return model;
    }

    protected CollectionTaskDefinitionView buildFileDefinition(Long modelId, String firstSourceField, String secondSourceField) {
        CollectionTaskDefinitionView definition = new CollectionTaskDefinitionView();
        definition.setTaskType(CollectionTaskType.SINGLE_TABLE);

        CollectionTaskSourceBinding sourceBinding = new CollectionTaskSourceBinding();
        sourceBinding.setDatasourceId(3L);
        sourceBinding.setModelId(modelId);
        sourceBinding.setSourceAlias("src1");
        definition.setSourceBindings(Collections.singletonList(sourceBinding));

        CollectionTaskTargetBinding targetBinding = new CollectionTaskTargetBinding();
        targetBinding.setDatasourceId(2L);
        targetBinding.setModelId(20L);
        definition.setTargetBinding(targetBinding);

        FieldMappingDefinition firstMapping = new FieldMappingDefinition();
        firstMapping.setSourceAlias("src1");
        firstMapping.setSourceField(firstSourceField);
        firstMapping.setTargetField("target_col");
        FieldMappingDefinition secondMapping = new FieldMappingDefinition();
        secondMapping.setSourceAlias("src1");
        secondMapping.setSourceField(secondSourceField);
        secondMapping.setTargetField("tag_value");
        definition.setFieldMappings(Arrays.asList(firstMapping, secondMapping));
        return definition;
    }

    protected CollectionTaskDefinitionView buildDefinition(TransformerBinding transformer) {
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

    protected CollectionTaskDefinitionView buildFusionDefinition() {
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

    protected CollectionTaskDefinitionView buildHttpDefinition(Long modelId) {
        CollectionTaskDefinitionView definition = new CollectionTaskDefinitionView();
        definition.setTaskType(CollectionTaskType.SINGLE_TABLE);

        CollectionTaskSourceBinding sourceBinding = new CollectionTaskSourceBinding();
        sourceBinding.setDatasourceId(4L);
        sourceBinding.setModelId(modelId);
        sourceBinding.setSourceAlias("src1");
        definition.setSourceBindings(Collections.singletonList(sourceBinding));

        CollectionTaskTargetBinding targetBinding = new CollectionTaskTargetBinding();
        targetBinding.setDatasourceId(2L);
        targetBinding.setModelId(20L);
        definition.setTargetBinding(targetBinding);

        FieldMappingDefinition mapping = new FieldMappingDefinition();
        mapping.setSourceAlias("src1");
        mapping.setSourceField("id");
        mapping.setTargetField("target_col");
        definition.setFieldMappings(Collections.singletonList(mapping));
        return definition;
    }

    protected CollectionTaskDefinitionView buildHttpWriterDefinition() {
        CollectionTaskDefinitionView definition = new CollectionTaskDefinitionView();
        definition.setTaskType(CollectionTaskType.SINGLE_TABLE);

        CollectionTaskSourceBinding sourceBinding = new CollectionTaskSourceBinding();
        sourceBinding.setDatasourceId(1L);
        sourceBinding.setModelId(10L);
        sourceBinding.setSourceAlias("src1");
        definition.setSourceBindings(Collections.singletonList(sourceBinding));

        CollectionTaskTargetBinding targetBinding = new CollectionTaskTargetBinding();
        targetBinding.setDatasourceId(4L);
        targetBinding.setModelId(42L);
        definition.setTargetBinding(targetBinding);

        FieldMappingDefinition idMapping = new FieldMappingDefinition();
        idMapping.setSourceAlias("src1");
        idMapping.setSourceField("source_col");
        idMapping.setTargetField("id");
        FieldMappingDefinition nameMapping = new FieldMappingDefinition();
        nameMapping.setSourceAlias("src1");
        nameMapping.setSourceField("source_col");
        nameMapping.setTargetField("name");
        definition.setFieldMappings(Arrays.asList(idMapping, nameMapping));
        return definition;
    }

    @SuppressWarnings("unchecked")
    protected List<Object> castList(Object value) {
        return value instanceof List ? (List<Object>) value : Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> castMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.<String, Object>emptyMap();
    }
}
