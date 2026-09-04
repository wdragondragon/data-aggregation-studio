package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.ManagedFileEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagedFileMaterializerTest {

    @TempDir
    Path tempDir;

    @Test
    void activeCacheEntrySurvivesCleanupUntilRuntimeLeaseIsReleased() throws Exception {
        byte[] content = "runtime-config=true\n".getBytes(StandardCharsets.UTF_8);
        ManagedFileEntity file = new ManagedFileEntity();
        file.setId(11L);
        file.setTenantId("tenant-a");
        file.setProjectId(21L);
        file.setOriginalFileName("runtime.conf");
        file.setPolicyCode("GENERAL_CONFIG");
        file.setPlaintextSize((long) content.length);
        file.setSha256(hex(MessageDigest.getInstance("SHA-256").digest(content)));

        ManagedFileService service = mock(ManagedFileService.class);
        when(service.requireReadyFile(11L, "tenant-a", 21L, "GENERAL_CONFIG")).thenReturn(file);
        ManagedFileService.LeaseRecord lease = new ManagedFileService.LeaseRecord(
                31L, "lease-token", LocalDateTime.now().plusMinutes(5));
        when(service.acquireLease(11L, "tenant-a", 21L,
                "TEST", "consumer", "worker")).thenReturn(lease);
        doAnswer(invocation -> {
            Files.write(invocation.getArgument(1, Path.class), content);
            return null;
        }).when(service).materialize(eq(file), any(Path.class));

        StudioPlatformProperties properties = new StudioPlatformProperties();
        StudioPlatformProperties.ManagedFileProperties managed = new StudioPlatformProperties.ManagedFileProperties();
        managed.setEnabled(true);
        managed.setCacheDir(tempDir.resolve("cache").toString());
        managed.setCacheMaxBytes(1L);
        managed.setCacheIdleHours(24);
        properties.setManagedFile(managed);
        ManagedFileMaterializer materializer = new ManagedFileMaterializer(
                service, new ManagedFilePolicyRegistry(), properties);

        ManagedFileMaterializer.MaterializedFile materialized = materializer.materialize(
                11L, "tenant-a", 21L, "GENERAL_CONFIG", "TEST", "consumer", "worker");
        materializer.cleanupCache();
        assertTrue(Files.isRegularFile(materialized.getPath()));

        materializer.release(materialized);
        materializer.cleanupCache();
        assertFalse(Files.exists(materialized.getPath()));
        verify(service).releaseLease("lease-token");
    }

    @Test
    void cleanupDoesNotDeleteStableMergedKerberosConfiguration() throws Exception {
        Path cacheRoot = tempDir.resolve("cache");
        Path mergedConfig = cacheRoot.resolve("kerberos").resolve("krb5-merged.conf");
        Files.createDirectories(mergedConfig.getParent());
        Files.writeString(mergedConfig, "[realms]\nTEST.REALM = { kdc = kdc.test }\n", StandardCharsets.UTF_8);

        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getManagedFile().setEnabled(true);
        properties.getManagedFile().setCacheDir(cacheRoot.toString());
        properties.getManagedFile().setCacheMaxBytes(1L);
        ManagedFileMaterializer materializer = new ManagedFileMaterializer(
                mock(ManagedFileService.class), new ManagedFilePolicyRegistry(), properties);

        materializer.cleanupCache();

        assertTrue(Files.isRegularFile(mergedConfig));
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }
}
