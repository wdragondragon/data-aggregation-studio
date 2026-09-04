package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.ManagedFileEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagedRuntimeFileResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void blocksLegacyPathsOnlyAfterFeatureIsEnabled() {
        StudioPlatformProperties properties = properties(true);
        ManagedRuntimeFileResolver resolver = resolver(properties, mock(ManagedFileMaterializer.class));
        try {
            DataSourceDefinition datasource = kafkaDatasource(Map.of(
                    "kerberosKeytabFilePath", "C:/legacy/client.keytab",
                    "krb5Conf", "C:/legacy/krb5.conf"));
            assertThrows(StudioException.class,
                    () -> resolver.resolveDatasource(datasource, "TEST", "legacy"));
        } finally {
            resolver.close();
        }

        properties.getManagedFile().setEnabled(false);
        ManagedRuntimeFileResolver compatible = resolver(properties, mock(ManagedFileMaterializer.class));
        try (ManagedRuntimeFileResolver.Resolution<DataSourceDefinition> resolution =
                     compatible.resolveDatasource(kafkaDatasource(Map.of(
                             "kerberosKeytabFilePath", "C:/legacy/client.keytab")),
                             "TEST", "compatible")) {
            assertEquals("C:/legacy/client.keytab",
                    resolution.getValue().getTechnicalMetadata().get("kerberosKeytabFilePath"));
            assertThrows(StudioException.class,
                    () -> compatible.resolveDatasource(kafkaDatasource(Map.of(
                            "kerberosKeytabFilePath", "managed-file://101")),
                            "TEST", "disabled"));
        } finally {
            compatible.close();
        }
    }

    @Test
    void materializesUriBeforePluginUseAndReleasesLeaseOnClose() throws Exception {
        byte[] content = new byte[]{0x05, 0x02, 0x01, 0x02};
        ManagedFileEntity file = new ManagedFileEntity();
        file.setId(101L);
        file.setTenantId("tenant-a");
        file.setProjectId(201L);
        file.setOriginalFileName("client.keytab");
        file.setPolicyCode("KERBEROS_KEYTAB");
        file.setPlaintextSize((long) content.length);
        file.setSha256(hex(MessageDigest.getInstance("SHA-256").digest(content)));

        ManagedFileService service = mock(ManagedFileService.class);
        when(service.requireReadyFile(101L, "tenant-a", 201L, "KERBEROS_KEYTAB")).thenReturn(file);
        ManagedFileService.LeaseRecord lease = new ManagedFileService.LeaseRecord(
                301L, "lease-token", LocalDateTime.now().plusMinutes(5));
        when(service.acquireLease(101L, "tenant-a", 201L,
                "TEST", "runtime", "worker-test")).thenReturn(lease);
        doAnswer(invocation -> {
            Files.write(invocation.getArgument(1, Path.class), content);
            return null;
        }).when(service).materialize(eq(file), any(Path.class));

        StudioPlatformProperties properties = properties(true);
        properties.setInstanceId("worker-test");
        ManagedFileMaterializer materializer = new ManagedFileMaterializer(
                service, new ManagedFilePolicyRegistry(), properties);
        ManagedRuntimeFileResolver resolver = resolver(properties, materializer);
        Path localPath;
        try {
            ManagedRuntimeFileResolver.Resolution<DataSourceDefinition> resolution =
                    resolver.resolveDatasource(kafkaDatasource(Map.of(
                            "kerberosKeytabFilePath", "managed-file://101")),
                            "TEST", "runtime");
            localPath = Path.of(String.valueOf(resolution.getValue().getTechnicalMetadata()
                    .get("kerberosKeytabFilePath")));
            assertTrue(Files.isRegularFile(localPath));
            assertEquals(content.length, Files.size(localPath));
            resolution.close();
            resolution.close();
        } finally {
            resolver.close();
        }
        verify(service).releaseLease("lease-token");
    }

    private ManagedRuntimeFileResolver resolver(StudioPlatformProperties properties,
                                                ManagedFileMaterializer materializer) {
        return new ManagedRuntimeFileResolver(materializer, mock(ManagedFileService.class), properties,
                new ClusterInstanceIdentity(properties), mock(KerberosConfigRegistry.class));
    }

    private StudioPlatformProperties properties(boolean enabled) {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        StudioPlatformProperties.ManagedFileProperties managed = new StudioPlatformProperties.ManagedFileProperties();
        managed.setEnabled(enabled);
        managed.setCacheDir(tempDir.resolve("cache").toString());
        managed.setLeaseHeartbeatSeconds(3600);
        properties.setManagedFile(managed);
        return properties;
    }

    private DataSourceDefinition kafkaDatasource(Map<String, Object> metadata) {
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setTenantId("tenant-a");
        datasource.setProjectId(201L);
        datasource.setTypeCode("kafka");
        datasource.setTechnicalMetadata(new LinkedHashMap<String, Object>(metadata));
        return datasource;
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }
}
