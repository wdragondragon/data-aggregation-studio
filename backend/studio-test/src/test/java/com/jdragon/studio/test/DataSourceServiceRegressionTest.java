package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.dto.ConnectionTestResult;
import com.jdragon.studio.dto.model.request.DataSourceSaveRequest;
import com.jdragon.studio.infra.entity.DatasourceEntity;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import com.jdragon.studio.infra.service.BusinessMetaModelMetadataService;
import com.jdragon.studio.infra.service.DataModelIndexRebuildQueueService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.DatasourceTypeCapabilityService;
import com.jdragon.studio.infra.service.EncryptionService;
import com.jdragon.studio.infra.service.MetadataSchemaService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataSourceServiceRegressionTest {

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        if (TableInfoHelper.getTableInfo(DatasourceEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), DatasourceEntity.class);
        }
    }

    @Test
    void shouldTestConnectionUsingCurrentFormMetadataAndPreserveMaskedSensitiveValues() {
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        AggregationSourceCapabilityProvider capabilityProvider = mock(AggregationSourceCapabilityProvider.class);
        MetadataSchemaService metadataSchemaService = mock(MetadataSchemaService.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        BusinessMetaModelMetadataService businessMetaModelMetadataService = mock(BusinessMetaModelMetadataService.class);

        DatasourceEntity existing = new DatasourceEntity();
        existing.setId(11L);
        Map<String, Object> existingMetadata = new LinkedHashMap<String, Object>();
        existingMetadata.put("host", "192.168.188.128");
        existingMetadata.put("password", "ENC(cipher)");
        existing.setTechnicalMetadata(existingMetadata);

        when(datasourceMapper.selectById(eq(11L))).thenReturn(existing);
        when(metadataSchemaService.findTechnicalMetaModel(eq("mysql8"), eq("source"))).thenReturn(null);
        when(encryptionService.decrypt(eq("cipher"))).thenReturn("SecretPass123");
        when(encryptionService.mask(eq("SecretPass123"))).thenReturn("Se****23");
        when(businessMetaModelMetadataService.normalizeForDatasource(any(Map.class))).thenReturn(new LinkedHashMap<String, Object>());

        ConnectionTestResult expected = new ConnectionTestResult();
        expected.setSuccess(true);
        expected.setMessage("Connection success");
        when(capabilityProvider.testConnection(any(DataSourceDefinition.class))).thenReturn(expected);

        DataSourceService service = new DataSourceService(
                datasourceMapper,
                mock(DataModelMapper.class),
                encryptionService,
                capabilityProvider,
                metadataSchemaService,
                mock(DataModelIndexRebuildQueueService.class),
                businessMetaModelMetadataService,
                mock(StudioSecurityService.class),
                mock(ProjectResourceAccessService.class),
                mock(DatasourceTypeCapabilityService.class)
        );

        DataSourceSaveRequest request = new DataSourceSaveRequest();
        request.setId(11L);
        request.setName("mysql_agg");
        request.setTypeCode("mysql8");
        request.setEnabled(true);
        request.setExecutable(true);
        Map<String, Object> requestMetadata = new LinkedHashMap<String, Object>();
        requestMetadata.put("host", "192.168.188.129");
        requestMetadata.put("password", "Se****23");
        request.setTechnicalMetadata(requestMetadata);
        request.setBusinessMetadata(new LinkedHashMap<String, Object>());

        ConnectionTestResult actual = service.testConnection(request);

        assertThat(actual).isSameAs(expected);

        ArgumentCaptor<DataSourceDefinition> captor = ArgumentCaptor.forClass(DataSourceDefinition.class);
        verify(capabilityProvider).testConnection(captor.capture());
        assertThat(captor.getValue().getTechnicalMetadata())
                .containsEntry("host", "192.168.188.129")
                .containsEntry("password", "ENC(cipher)");
        verify(datasourceMapper, never()).update(any(), any());
    }

    @Test
    void shouldPersistConnectionSnapshotWhenTestingSavedDatasource() {
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        AggregationSourceCapabilityProvider capabilityProvider = mock(AggregationSourceCapabilityProvider.class);
        MetadataSchemaService metadataSchemaService = mock(MetadataSchemaService.class);
        BusinessMetaModelMetadataService businessMetaModelMetadataService = mock(BusinessMetaModelMetadataService.class);
        ProjectResourceAccessService projectResourceAccessService = mock(ProjectResourceAccessService.class);
        DatasourceTypeCapabilityService datasourceTypeCapabilityService = mock(DatasourceTypeCapabilityService.class);

        DatasourceEntity existing = datasourceEntity("mysql_source", "mysql8", 7L, "127.0.0.1");
        existing.setEnabled(1);
        existing.setExecutable(1);
        when(datasourceMapper.selectById(eq(11L))).thenReturn(existing);
        when(metadataSchemaService.findSchemaByVersionId(eq(7L))).thenReturn(null);
        when(metadataSchemaService.listSchemas()).thenReturn(Collections.emptyList());
        when(businessMetaModelMetadataService.normalizeForDatasource(any(Map.class))).thenReturn(new LinkedHashMap<String, Object>());

        ConnectionTestResult expected = new ConnectionTestResult();
        expected.setSuccess(false);
        expected.setMessage("Connection refused");
        when(capabilityProvider.testConnection(any(DataSourceDefinition.class))).thenReturn(expected);

        DataSourceService service = new DataSourceService(
                datasourceMapper,
                mock(DataModelMapper.class),
                mock(EncryptionService.class),
                capabilityProvider,
                metadataSchemaService,
                mock(DataModelIndexRebuildQueueService.class),
                businessMetaModelMetadataService,
                mock(StudioSecurityService.class),
                projectResourceAccessService,
                datasourceTypeCapabilityService
        );

        ConnectionTestResult actual = service.testConnection(11L);

        assertThat(actual.getStatus()).hasToString("UNAVAILABLE");
        assertThat(actual.getDurationMs()).isNotNull();
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DatasourceEntity>> wrapperCaptor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(datasourceMapper).update(isNull(), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSet())
                .contains("connectionStatus", "lastConnectionTestAt", "lastConnectionTestMessage", "lastConnectionTestDurationMs");
    }

    @Test
    void shouldResetConnectionSnapshotWhenConnectionMetadataChangesOnSave() {
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        AggregationSourceCapabilityProvider capabilityProvider = mock(AggregationSourceCapabilityProvider.class);
        MetadataSchemaService metadataSchemaService = mock(MetadataSchemaService.class);
        BusinessMetaModelMetadataService businessMetaModelMetadataService = mock(BusinessMetaModelMetadataService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService projectResourceAccessService = mock(ProjectResourceAccessService.class);

        DatasourceEntity existing = datasourceEntity("mysql_source", "mysql8", 7L, "127.0.0.1");
        existing.setConnectionStatus("AVAILABLE");
        existing.setLastConnectionTestAt(LocalDateTime.now().minusMinutes(10));
        existing.setLastConnectionTestMessage("Connection success");
        existing.setLastConnectionTestDurationMs(12L);
        when(datasourceMapper.selectById(eq(11L))).thenReturn(existing);
        when(datasourceMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(metadataSchemaService.findSchemaByVersionId(eq(7L))).thenReturn(null);
        when(metadataSchemaService.listSchemas()).thenReturn(Collections.emptyList());
        when(businessMetaModelMetadataService.normalizeForDatasource(any(Map.class))).thenReturn(new LinkedHashMap<String, Object>());
        when(projectResourceAccessService.requireCurrentProjectId()).thenReturn(3L);
        when(securityService.currentTenantId()).thenReturn("default");

        DataSourceService service = new DataSourceService(
                datasourceMapper,
                mock(DataModelMapper.class),
                mock(EncryptionService.class),
                capabilityProvider,
                metadataSchemaService,
                mock(DataModelIndexRebuildQueueService.class),
                businessMetaModelMetadataService,
                securityService,
                projectResourceAccessService,
                mock(DatasourceTypeCapabilityService.class)
        );

        DataSourceSaveRequest request = datasourceRequest(11L, "mysql_source", "mysql8", 7L, "192.168.188.129");

        DataSourceDefinition saved = service.save(request);

        assertThat(saved.getConnectionStatus()).hasToString("UNKNOWN");
        assertThat(saved.getLastConnectionTestAt()).isNull();
        ArgumentCaptor<DatasourceEntity> entityCaptor = ArgumentCaptor.forClass(DatasourceEntity.class);
        verify(datasourceMapper).updateById(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getConnectionStatus()).isEqualTo("UNKNOWN");
        verify(datasourceMapper).update(isNull(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class));
    }

    @Test
    void shouldKeepConnectionSnapshotWhenOnlyDatasourceDisplayFieldsChangeOnSave() {
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        AggregationSourceCapabilityProvider capabilityProvider = mock(AggregationSourceCapabilityProvider.class);
        MetadataSchemaService metadataSchemaService = mock(MetadataSchemaService.class);
        BusinessMetaModelMetadataService businessMetaModelMetadataService = mock(BusinessMetaModelMetadataService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService projectResourceAccessService = mock(ProjectResourceAccessService.class);

        DatasourceEntity existing = datasourceEntity("mysql_source", "mysql8", 7L, "127.0.0.1");
        existing.setConnectionStatus("AVAILABLE");
        existing.setLastConnectionTestAt(LocalDateTime.now().minusMinutes(10));
        existing.setLastConnectionTestMessage("Connection success");
        existing.setLastConnectionTestDurationMs(12L);
        when(datasourceMapper.selectById(eq(11L))).thenReturn(existing);
        when(datasourceMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(metadataSchemaService.findSchemaByVersionId(eq(7L))).thenReturn(null);
        when(metadataSchemaService.listSchemas()).thenReturn(Collections.emptyList());
        when(businessMetaModelMetadataService.normalizeForDatasource(any(Map.class))).thenReturn(new LinkedHashMap<String, Object>());
        when(projectResourceAccessService.requireCurrentProjectId()).thenReturn(3L);
        when(securityService.currentTenantId()).thenReturn("default");

        DataSourceService service = new DataSourceService(
                datasourceMapper,
                mock(DataModelMapper.class),
                mock(EncryptionService.class),
                capabilityProvider,
                metadataSchemaService,
                mock(DataModelIndexRebuildQueueService.class),
                businessMetaModelMetadataService,
                securityService,
                projectResourceAccessService,
                mock(DatasourceTypeCapabilityService.class)
        );

        DataSourceSaveRequest request = datasourceRequest(11L, "renamed_source", "mysql8", 7L, "127.0.0.1");

        DataSourceDefinition saved = service.save(request);

        assertThat(saved.getConnectionStatus()).hasToString("AVAILABLE");
        assertThat(saved.getLastConnectionTestMessage()).isEqualTo("Connection success");
        verify(datasourceMapper, never()).update(isNull(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class));
    }

    private DatasourceEntity datasourceEntity(String name, String typeCode, Long schemaVersionId, String host) {
        DatasourceEntity entity = new DatasourceEntity();
        entity.setId(11L);
        entity.setTenantId("default");
        entity.setProjectId(3L);
        entity.setName(name);
        entity.setTypeCode(typeCode);
        entity.setSchemaVersionId(schemaVersionId);
        entity.setEnabled(1);
        entity.setExecutable(1);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("host", host);
        entity.setTechnicalMetadata(metadata);
        entity.setBusinessMetadata(new LinkedHashMap<String, Object>());
        return entity;
    }

    private DataSourceSaveRequest datasourceRequest(Long id, String name, String typeCode, Long schemaVersionId, String host) {
        DataSourceSaveRequest request = new DataSourceSaveRequest();
        request.setId(id);
        request.setName(name);
        request.setTypeCode(typeCode);
        request.setSchemaVersionId(schemaVersionId);
        request.setEnabled(true);
        request.setExecutable(true);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("host", host);
        request.setTechnicalMetadata(metadata);
        request.setBusinessMetadata(new LinkedHashMap<String, Object>());
        return request;
    }
}
