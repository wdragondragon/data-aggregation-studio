package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.enums.RuntimeDatasourceProbeMode;
import com.jdragon.studio.dto.model.dto.ConnectionTestResult;
import com.jdragon.studio.dto.model.request.DataSourceSaveRequest;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.entity.DatasourceEntity;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import com.jdragon.studio.infra.service.BusinessMetaModelMetadataService;
import com.jdragon.studio.infra.service.DataModelIndexRebuildQueueService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.DatasourceConnectionFingerprintService;
import com.jdragon.studio.infra.service.DatasourceConnectionHealthService;
import com.jdragon.studio.infra.service.DatasourceTypeCapabilityService;
import com.jdragon.studio.infra.service.EncryptionService;
import com.jdragon.studio.infra.service.MetadataSchemaService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.RuntimeDatasourceProbeRouter;
import com.jdragon.studio.infra.service.StudioSecurityService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class DataSourceServiceRegressionTest {

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        if (TableInfoHelper.getTableInfo(DatasourceEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DatasourceEntity.class);
        }
    }

    @Test
    void shouldRejectDisabledDatasourceForNewExecution() {
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        DatasourceEntity entity = datasourceEntity("disabled_source", "mysql8", 7L, "db.example");
        entity.setEnabled(0);
        when(datasourceMapper.selectById(11L)).thenReturn(entity);
        when(securityService.currentTenantId()).thenReturn("default");

        DataSourceService service = new DataSourceService(
                datasourceMapper,
                mock(DataModelMapper.class),
                mock(EncryptionService.class),
                mock(MetadataSchemaService.class),
                mock(DataModelIndexRebuildQueueService.class),
                mock(BusinessMetaModelMetadataService.class),
                securityService,
                mock(ProjectResourceAccessService.class),
                mock(DatasourceTypeCapabilityService.class),
                mock(DatasourceConnectionFingerprintService.class),
                mock(DatasourceConnectionHealthService.class));

        assertThatThrownBy(() -> service.requireRunnableForExecution(11L))
                .isInstanceOf(StudioException.class)
                .hasMessageContaining("enabled and executable");
    }

    @Test
    void shouldOmitSensitiveMetadataFromPublicDatasourceDetail() {
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        DatasourceConnectionHealthService connectionHealthService = mock(DatasourceConnectionHealthService.class);
        DatasourceEntity entity = datasourceEntity("mysql_source", "mysql8", 7L, "127.0.0.1");
        entity.setTenantId("default");
        entity.getTechnicalMetadata().put("password", "ENC(ciphertext)");
        entity.getTechnicalMetadata().put("accessToken", "legacy-plain-token");
        entity.getTechnicalMetadata().put("apiKey", "legacy-plain-api-key");

        when(datasourceMapper.selectById(eq(11L))).thenReturn(entity);
        when(securityService.currentTenantId()).thenReturn("default");

        DataSourceService service = new DataSourceService(
                datasourceMapper,
                mock(DataModelMapper.class),
                encryptionService,
                mock(MetadataSchemaService.class),
                mock(DataModelIndexRebuildQueueService.class),
                mock(BusinessMetaModelMetadataService.class),
                securityService,
                mock(ProjectResourceAccessService.class),
                mock(DatasourceTypeCapabilityService.class),
                mock(DatasourceConnectionFingerprintService.class),
                connectionHealthService
        );

        DataSourceDefinition actual = service.get(11L);

        assertThat(actual.getTechnicalMetadata())
                .containsEntry("host", "127.0.0.1")
                .doesNotContainKeys("password", "accessToken", "apiKey");
        assertThat(actual.getSavedSensitiveFieldKeys()).containsExactlyInAnyOrder("password", "accessToken", "apiKey");
        verifyNoInteractions(encryptionService);
    }

    @Test
    void shouldPreserveAndEncryptLegacySecretWhenPublicUpdateOmitsIt() {
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        MetadataSchemaService metadataSchemaService = mock(MetadataSchemaService.class);
        BusinessMetaModelMetadataService businessMetaModelMetadataService = mock(BusinessMetaModelMetadataService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService projectResourceAccessService = mock(ProjectResourceAccessService.class);
        DatasourceConnectionFingerprintService fingerprintService = mock(DatasourceConnectionFingerprintService.class);
        DatasourceConnectionHealthService connectionHealthService = mock(DatasourceConnectionHealthService.class);
        DatasourceEntity existing = datasourceEntity("mysql_source", "mysql8", 7L, "127.0.0.1");
        existing.getTechnicalMetadata().put("password", "legacy-plain-secret");

        when(datasourceMapper.selectById(eq(11L))).thenReturn(existing);
        when(datasourceMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(metadataSchemaService.findSchemaByVersionId(eq(7L))).thenReturn(null);
        when(metadataSchemaService.listSchemas()).thenReturn(Collections.emptyList());
        when(businessMetaModelMetadataService.normalizeForDatasource(any(Map.class))).thenReturn(new LinkedHashMap<String, Object>());
        when(projectResourceAccessService.requireCurrentProjectId()).thenReturn(3L);
        when(securityService.currentTenantId()).thenReturn("default");
        when(fingerprintService.fingerprint(eq("default"), eq("mysql8"), any(Map.class))).thenReturn("fp-legacy-secret");
        when(encryptionService.encrypt(eq("legacy-plain-secret"))).thenReturn("ciphertext");

        DataSourceService service = new DataSourceService(
                datasourceMapper,
                mock(DataModelMapper.class),
                encryptionService,
                metadataSchemaService,
                mock(DataModelIndexRebuildQueueService.class),
                businessMetaModelMetadataService,
                securityService,
                projectResourceAccessService,
                mock(DatasourceTypeCapabilityService.class),
                fingerprintService,
                connectionHealthService
        );
        DataSourceSaveRequest request = datasourceRequest(11L, "renamed_source", "mysql8", 7L, "127.0.0.1");

        DataSourceDefinition saved = service.save(request);

        ArgumentCaptor<DatasourceEntity> entityCaptor = ArgumentCaptor.forClass(DatasourceEntity.class);
        verify(datasourceMapper).updateById(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getTechnicalMetadata()).containsEntry("password", "ENC(ciphertext)");
        assertThat(saved.getTechnicalMetadata()).doesNotContainKey("password");
        assertThat(saved.getSavedSensitiveFieldKeys()).contains("password");
    }

    @Test
    void shouldTestConnectionUsingCurrentFormMetadataAndPreserveMaskedSensitiveValues() {
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        RuntimeDatasourceProbeRouter probeRouter = mock(RuntimeDatasourceProbeRouter.class);
        MetadataSchemaService metadataSchemaService = mock(MetadataSchemaService.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        BusinessMetaModelMetadataService businessMetaModelMetadataService = mock(BusinessMetaModelMetadataService.class);
        DatasourceConnectionHealthService datasourceConnectionHealthService = mock(DatasourceConnectionHealthService.class);

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
        when(probeRouter.test(any(DataSourceDefinition.class), eq(46L),
                eq(RuntimeDatasourceProbeMode.DRAFT_FORM))).thenReturn(expected);
        when(datasourceConnectionHealthService.effectiveManualTimeout(any(DataSourceDefinition.class))).thenReturn(30);
        when(datasourceConnectionHealthService.runCurrentFormProbe(any(Callable.class), eq(30))).thenAnswer(invocation ->
                ((Callable<ConnectionTestResult>) invocation.getArgument(0)).call());

        DataSourceService service = new DataSourceService(
                datasourceMapper,
                mock(DataModelMapper.class),
                encryptionService,
                metadataSchemaService,
                mock(DataModelIndexRebuildQueueService.class),
                businessMetaModelMetadataService,
                mock(StudioSecurityService.class),
                mock(ProjectResourceAccessService.class),
                mock(DatasourceTypeCapabilityService.class),
                mock(DatasourceConnectionFingerprintService.class),
                datasourceConnectionHealthService
        );
        service.setRuntimeDatasourceProbeRouter(probeRouter);

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

        ConnectionTestResult actual = service.testConnection(request, 46L);

        assertThat(actual).isSameAs(expected);

        ArgumentCaptor<DataSourceDefinition> captor = ArgumentCaptor.forClass(DataSourceDefinition.class);
        verify(probeRouter).test(captor.capture(), eq(46L),
                eq(RuntimeDatasourceProbeMode.DRAFT_FORM));
        assertThat(captor.getValue().getTechnicalMetadata())
                .containsEntry("host", "192.168.188.129")
                .containsEntry("password", "ENC(cipher)");
        verify(datasourceMapper, never()).update(any(), any());
    }

    @Test
    void shouldPersistConnectionSnapshotWhenTestingSavedDatasource() {
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        RuntimeDatasourceProbeRouter probeRouter = mock(RuntimeDatasourceProbeRouter.class);
        MetadataSchemaService metadataSchemaService = mock(MetadataSchemaService.class);
        BusinessMetaModelMetadataService businessMetaModelMetadataService = mock(BusinessMetaModelMetadataService.class);
        ProjectResourceAccessService projectResourceAccessService = mock(ProjectResourceAccessService.class);
        DatasourceTypeCapabilityService datasourceTypeCapabilityService = mock(DatasourceTypeCapabilityService.class);
        DatasourceConnectionHealthService datasourceConnectionHealthService = mock(DatasourceConnectionHealthService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);

        DatasourceEntity existing = datasourceEntity("mysql_source", "mysql8", 7L, "127.0.0.1");
        existing.setEnabled(1);
        existing.setExecutable(1);
        when(datasourceMapper.selectById(eq(11L))).thenReturn(existing);
        when(metadataSchemaService.findSchemaByVersionId(eq(7L))).thenReturn(null);
        when(metadataSchemaService.listSchemas()).thenReturn(Collections.emptyList());
        when(businessMetaModelMetadataService.normalizeForDatasource(any(Map.class))).thenReturn(new LinkedHashMap<String, Object>());
        when(securityService.currentTenantId()).thenReturn("default");

        ConnectionTestResult expected = new ConnectionTestResult();
        expected.setSuccess(false);
        expected.setMessage("Connection refused");
        expected.setStatus(com.jdragon.studio.dto.enums.DataSourceConnectionStatus.UNAVAILABLE);
        expected.setDurationMs(3L);
        when(probeRouter.test(any(DataSourceDefinition.class), eq(46L),
                eq(RuntimeDatasourceProbeMode.STORED))).thenReturn(expected);
        when(datasourceConnectionHealthService.effectiveManualTimeout(any(DataSourceDefinition.class))).thenReturn(30);
        when(datasourceConnectionHealthService.runManualProbe(any(DataSourceDefinition.class), eq(46L),
                any(Callable.class), eq(30))).thenAnswer(invocation ->
                ((Callable<ConnectionTestResult>) invocation.getArgument(2)).call());

        DataSourceService service = new DataSourceService(
                datasourceMapper,
                mock(DataModelMapper.class),
                mock(EncryptionService.class),
                metadataSchemaService,
                mock(DataModelIndexRebuildQueueService.class),
                businessMetaModelMetadataService,
                securityService,
                projectResourceAccessService,
                datasourceTypeCapabilityService,
                mock(DatasourceConnectionFingerprintService.class),
                datasourceConnectionHealthService
        );
        service.setRuntimeDatasourceProbeRouter(probeRouter);

        ConnectionTestResult actual = service.testConnection(11L, 46L);

        assertThat(actual.getStatus()).hasToString("UNAVAILABLE");
        assertThat(actual.getDurationMs()).isNotNull();
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DatasourceEntity>> wrapperCaptor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(datasourceMapper).update(isNull(), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSet())
                .contains("connection_status", "last_connection_test_at", "last_connection_test_message", "last_connection_test_duration_ms");
    }

    @Test
    void shouldResetConnectionSnapshotWhenConnectionMetadataChangesOnSave() {
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        MetadataSchemaService metadataSchemaService = mock(MetadataSchemaService.class);
        BusinessMetaModelMetadataService businessMetaModelMetadataService = mock(BusinessMetaModelMetadataService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService projectResourceAccessService = mock(ProjectResourceAccessService.class);
        DatasourceConnectionFingerprintService datasourceConnectionFingerprintService = mock(DatasourceConnectionFingerprintService.class);
        DatasourceConnectionHealthService datasourceConnectionHealthService = mock(DatasourceConnectionHealthService.class);

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
        when(datasourceConnectionFingerprintService.fingerprint(eq("default"), eq("mysql8"), any(Map.class))).thenReturn("fp-new");

        DataSourceService service = new DataSourceService(
                datasourceMapper,
                mock(DataModelMapper.class),
                mock(EncryptionService.class),
                metadataSchemaService,
                mock(DataModelIndexRebuildQueueService.class),
                businessMetaModelMetadataService,
                securityService,
                projectResourceAccessService,
                mock(DatasourceTypeCapabilityService.class),
                datasourceConnectionFingerprintService,
                datasourceConnectionHealthService
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
        MetadataSchemaService metadataSchemaService = mock(MetadataSchemaService.class);
        BusinessMetaModelMetadataService businessMetaModelMetadataService = mock(BusinessMetaModelMetadataService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService projectResourceAccessService = mock(ProjectResourceAccessService.class);
        DatasourceConnectionFingerprintService datasourceConnectionFingerprintService = mock(DatasourceConnectionFingerprintService.class);
        DatasourceConnectionHealthService datasourceConnectionHealthService = mock(DatasourceConnectionHealthService.class);

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
        when(datasourceConnectionFingerprintService.fingerprint(eq("default"), eq("mysql8"), any(Map.class))).thenReturn("fp-127.0.0.1");

        DataSourceService service = new DataSourceService(
                datasourceMapper,
                mock(DataModelMapper.class),
                mock(EncryptionService.class),
                metadataSchemaService,
                mock(DataModelIndexRebuildQueueService.class),
                businessMetaModelMetadataService,
                securityService,
                projectResourceAccessService,
                mock(DatasourceTypeCapabilityService.class),
                datasourceConnectionFingerprintService,
                datasourceConnectionHealthService
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
        entity.setConnectionFingerprint("fp-" + host);
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
