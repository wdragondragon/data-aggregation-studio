package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.FieldComponentType;
import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.enums.ManagedFileStatus;
import com.jdragon.studio.dto.model.MetadataFieldDefinition;
import com.jdragon.studio.dto.model.MetadataSchemaDefinition;
import com.jdragon.studio.dto.model.ManagedFileMigrationIssueView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DatasourceEntity;
import com.jdragon.studio.infra.entity.ManagedFileEntity;
import com.jdragon.studio.infra.entity.ManagedFileReferenceEntity;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import com.jdragon.studio.infra.mapper.ManagedFileAuditMapper;
import com.jdragon.studio.infra.mapper.ManagedFileLeaseMapper;
import com.jdragon.studio.infra.mapper.ManagedFileMapper;
import com.jdragon.studio.infra.mapper.ManagedFileReferenceMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagedFileServiceLifecycleTest {

    @BeforeAll
    static void initializeManagedFileTableInfo() {
        if (TableInfoHelper.getTableInfo(ManagedFileEntity.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), "managed-file-lifecycle-test"),
                    ManagedFileEntity.class);
        }
    }

    @Test
    void migrationIssuesExposeOnlyDatasourceAndFieldNames() {
        Fixture fixture = fixture();
        DatasourceEntity kafka = new DatasourceEntity();
        kafka.setId(11L);
        kafka.setTenantId("tenant-a");
        kafka.setProjectId(21L);
        kafka.setName("legacy-kafka");
        kafka.setTypeCode("kafka");
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("kerberosKeytabFilePath", "C:/secret/client.keytab");
        metadata.put("krb5Conf", "managed-file://101");
        kafka.setTechnicalMetadata(metadata);
        when(fixture.datasourceMapper.selectList(any())).thenReturn(List.of(kafka));

        List<ManagedFileMigrationIssueView> issues = fixture.service.migrationIssues();

        assertEquals(1, issues.size());
        assertEquals(List.of("kerberosKeytabFilePath"), issues.get(0).getFieldKeys());
        assertEquals("legacy-kafka", issues.get(0).getDatasourceName());
    }

    @Test
    void referencedFileCannotBeDeleted() {
        Fixture fixture = fixture();
        ManagedFileEntity file = readyFile(31L);
        when(fixture.fileMapper.selectOne(any())).thenReturn(file);
        ManagedFileReferenceEntity reference = new ManagedFileReferenceEntity();
        reference.setFileId(31L);
        reference.setOwnerType("DATASOURCE");
        reference.setOwnerId(41L);
        reference.setFieldKey("krb5Conf");
        when(fixture.referenceMapper.selectList(any())).thenReturn(List.of(reference));

        StudioException error = assertThrows(StudioException.class,
                () -> fixture.service.requestDelete(31L));

        assertTrue(error.getMessage().contains("DATASOURCE:41:krb5Conf"));
        verify(fixture.fileMapper, never()).updateById(any(ManagedFileEntity.class));
    }

    @Test
    void activeLeasePreventsObjectDeletion() {
        Fixture fixture = fixture();
        ManagedFileEntity pending = pendingFile(51L);
        when(fixture.fileMapper.selectList(any()))
                .thenReturn(Collections.emptyList(), List.of(pending));
        when(fixture.referenceMapper.selectCount(any())).thenReturn(0L);
        when(fixture.leaseMapper.selectCount(any())).thenReturn(1L);

        fixture.service.garbageCollect();

        verify(fixture.objectStorage, never()).exists(any(), any());
        verify(fixture.objectStorage, never()).delete(any(), any());
    }

    @Test
    void objectDeletionFailureMovesFileToRetryState() {
        Fixture fixture = fixture();
        ManagedFileEntity pending = pendingFile(61L);
        when(fixture.fileMapper.selectList(any()))
                .thenReturn(Collections.emptyList(), List.of(pending));
        when(fixture.referenceMapper.selectCount(any())).thenReturn(0L);
        when(fixture.leaseMapper.selectCount(any())).thenReturn(0L);
        when(fixture.objectStorage.exists("managed", "object/61")).thenReturn(true);
        doThrow(new IllegalStateException("storage unavailable"))
                .when(fixture.objectStorage).delete("managed", "object/61");

        fixture.service.garbageCollect();

        assertEquals(ManagedFileStatus.DELETE_FAILED.name(), pending.getStatus());
        assertEquals(1, pending.getDeleteRetryCount());
        assertTrue(pending.getNextDeleteAttemptAt().isAfter(LocalDateTime.now()));
        verify(fixture.fileMapper).updateById(pending);
    }

    @Test
    void synchronizingReferencesPhysicallyReplacesExistingOwnerRows() {
        Fixture fixture = fixture();
        ManagedFileEntity file = readyFile(71L);
        when(fixture.fileMapper.selectOne(any())).thenReturn(file);
        when(fixture.fileMapper.update(any(), any())).thenReturn(1);
        ManagedFileReferenceEntity existing = new ManagedFileReferenceEntity();
        existing.setFileId(71L);
        existing.setFieldKey("krb5Conf");
        when(fixture.referenceMapper.selectList(any())).thenReturn(List.of(existing));

        MetadataFieldDefinition field = new MetadataFieldDefinition();
        field.setFieldKey("krb5Conf");
        field.setValueType(FieldValueType.STRING);
        field.setComponentType(FieldComponentType.MANAGED_FILE);
        field.setFilePolicyCode("KERBEROS_KEYTAB");
        MetadataSchemaDefinition schema = new MetadataSchemaDefinition();
        schema.setFields(List.of(field));

        fixture.service.synchronizeReferences("DATASOURCE", 81L, schema,
                Collections.singletonMap("krb5Conf", "managed-file://71"));

        verify(fixture.referenceMapper).hardDeleteByOwner("tenant-a", 21L, "DATASOURCE", 81L);
        verify(fixture.referenceMapper).insert(any(ManagedFileReferenceEntity.class));
    }

    @Test
    void garbageCollectionPhysicallyPurgesExpiredAuditAndLeaseRows() {
        Fixture fixture = fixture();
        when(fixture.fileMapper.selectList(any())).thenReturn(Collections.emptyList());

        fixture.service.garbageCollect();

        verify(fixture.auditMapper).hardDeleteBefore(any(LocalDateTime.class));
        verify(fixture.leaseMapper).hardDeleteExpired(any(LocalDateTime.class));
    }

    private Fixture fixture() {
        ManagedFileMapper fileMapper = mock(ManagedFileMapper.class);
        ManagedFileReferenceMapper referenceMapper = mock(ManagedFileReferenceMapper.class);
        ManagedFileLeaseMapper leaseMapper = mock(ManagedFileLeaseMapper.class);
        ManagedFileAuditMapper auditMapper = mock(ManagedFileAuditMapper.class);
        CloudObjectStorageService objectStorage = mock(CloudObjectStorageService.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        ProjectResourceAccessService access = mock(ProjectResourceAccessService.class);
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(security.currentUserId()).thenReturn(1L);
        when(security.currentUsername()).thenReturn("admin");
        when(security.hasAnyRole(any(String[].class))).thenReturn(true);
        when(access.requireCurrentProjectId()).thenReturn(21L);
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setEncryptionSecret("managed-file-lifecycle-test");
        properties.getManagedFile().setEnabled(true);
        ManagedFileService service = new ManagedFileService(fileMapper, referenceMapper, leaseMapper,
                auditMapper, new ManagedFilePolicyRegistry(), new ManagedFileCryptoService(properties),
                objectStorage, properties, security, access, datasourceMapper);
        return new Fixture(service, fileMapper, referenceMapper, leaseMapper,
                auditMapper, objectStorage, datasourceMapper);
    }

    private ManagedFileEntity readyFile(Long id) {
        ManagedFileEntity file = pendingFile(id);
        file.setStatus(ManagedFileStatus.READY.name());
        return file;
    }

    private ManagedFileEntity pendingFile(Long id) {
        ManagedFileEntity file = new ManagedFileEntity();
        file.setId(id);
        file.setTenantId("tenant-a");
        file.setProjectId(21L);
        file.setOriginalFileName("client.keytab");
        file.setPolicyCode("KERBEROS_KEYTAB");
        file.setStatus(ManagedFileStatus.DELETE_PENDING.name());
        file.setObjectBucket("managed");
        file.setObjectKey("object/" + id);
        file.setDeleteRetryCount(0);
        file.setNextDeleteAttemptAt(LocalDateTime.now().minusMinutes(1));
        return file;
    }

    private static final class Fixture {
        private final ManagedFileService service;
        private final ManagedFileMapper fileMapper;
        private final ManagedFileReferenceMapper referenceMapper;
        private final ManagedFileLeaseMapper leaseMapper;
        private final ManagedFileAuditMapper auditMapper;
        private final CloudObjectStorageService objectStorage;
        private final DatasourceMapper datasourceMapper;

        private Fixture(ManagedFileService service, ManagedFileMapper fileMapper,
                        ManagedFileReferenceMapper referenceMapper,
                        ManagedFileLeaseMapper leaseMapper,
                        ManagedFileAuditMapper auditMapper,
                        CloudObjectStorageService objectStorage,
                        DatasourceMapper datasourceMapper) {
            this.service = service;
            this.fileMapper = fileMapper;
            this.referenceMapper = referenceMapper;
            this.leaseMapper = leaseMapper;
            this.auditMapper = auditMapper;
            this.objectStorage = objectStorage;
            this.datasourceMapper = datasourceMapper;
        }
    }
}
