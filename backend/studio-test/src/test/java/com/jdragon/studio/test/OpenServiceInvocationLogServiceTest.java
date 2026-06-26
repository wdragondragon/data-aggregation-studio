package com.jdragon.studio.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.CloudObjectStorageService;
import com.jdragon.studio.infra.service.OpenServiceInvocationLogService;
import com.jdragon.studio.infra.service.RunLogObjectStore;
import com.jdragon.studio.infra.service.RunLogStorageService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenServiceInvocationLogServiceTest {

    @Test
    void invocationLogArchiveShouldInheritLocalRunLogStorageByDefault() {
        CountingObjectStore objectStore = new CountingObjectStore();
        OpenServiceInvocationLogService service = invocationLogService(new StudioPlatformProperties(), objectStore);

        OpenServiceInvocationLogService.ArchiveResult result = service.archive(
                OpenServiceInvocationLogService.DOMAIN_DATA_SERVICES,
                "data-service",
                "request-1",
                LocalDateTime.of(2026, 6, 14, 1, 2, 3),
                "hello");

        assertEquals(OpenServiceInvocationLogService.ARCHIVE_SKIPPED, result.getLogArchiveStatus());
        assertEquals(RunLogStorageService.STORAGE_LOCAL, result.getLogStorageType());
        assertNull(result.getLogArchiveError());
        assertEquals(0, objectStore.putCount);
    }

    @Test
    void invocationLogArchiveShouldSkipObjectStorageWhenBucketIsMissing() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getRunLog().setStorageType(RunLogStorageService.STORAGE_OBJECT);
        properties.getInvocationLog().setStorageType(RunLogStorageService.STORAGE_OBJECT);
        CountingObjectStore objectStore = new CountingObjectStore();
        OpenServiceInvocationLogService service = invocationLogService(properties, objectStore);

        OpenServiceInvocationLogService.ArchiveResult result = service.archive(
                OpenServiceInvocationLogService.DOMAIN_PROTOCOL_CONVERSIONS,
                "protocol-conversion",
                "request-2",
                LocalDateTime.of(2026, 6, 14, 1, 2, 3),
                "hello");

        assertEquals(OpenServiceInvocationLogService.ARCHIVE_SKIPPED, result.getLogArchiveStatus());
        assertEquals(RunLogStorageService.STORAGE_OBJECT, result.getLogStorageType());
        assertNull(result.getLogArchiveError());
        assertEquals(0, objectStore.putCount);
    }

    private static OpenServiceInvocationLogService invocationLogService(StudioPlatformProperties properties,
                                                                        CountingObjectStore objectStore) {
        CloudObjectStorageService cloudObjectStorageService = mock(CloudObjectStorageService.class);
        when(cloudObjectStorageService.bucketConfigured()).thenReturn(false);
        return new OpenServiceInvocationLogService(
                properties,
                new RunLogStorageService(properties, objectStore, cloudObjectStorageService),
                null,
                null,
                null,
                null,
                null,
                new ObjectMapper());
    }

    private static final class CountingObjectStore implements RunLogObjectStore {
        private int putCount;

        @Override
        public void put(String bucket, String objectKey, byte[] bytes, String contentType) {
            putCount++;
        }

        @Override
        public byte[] get(String bucket, String objectKey) {
            return new byte[0];
        }

        @Override
        public boolean available() {
            return true;
        }
    }
}
