package com.jdragon.studio.test;

import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RunLogChunkEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.RunLogChunkMapper;
import com.jdragon.studio.infra.service.RunLogObjectStore;
import com.jdragon.studio.infra.service.RunLogStorageService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunLogStorageServiceRegressionTest {

    @Test
    void objectLogPagingShouldNotSplitUtf8Characters() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getRunLog().setStorageType(RunLogStorageService.STORAGE_OBJECT);
        properties.getRunLog().getObjectStorage().setBucket("logs");
        InMemoryObjectStore objectStore = new InMemoryObjectStore();
        objectStore.put("logs", "run.log", "abc中文def".getBytes(StandardCharsets.UTF_8), "text/plain;charset=UTF-8");

        RunRecordEntity entity = new RunRecordEntity();
        entity.setId(1L);
        entity.setLogObjectBucket("logs");
        entity.setLogObjectKey("run.log");
        entity.setLogCharset("UTF-8");

        RunLogView view = new RunLogStorageService(properties, objectStore, null)
                .readObjectLog(entity, 2, 4, false);

        assertFalse(view.getContent().contains("\uFFFD"));
    }

    @Test
    void objectLogReadShouldDefensivelyRedactSensitiveValues() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getRunLog().setStorageType(RunLogStorageService.STORAGE_OBJECT);
        InMemoryObjectStore objectStore = new InMemoryObjectStore();
        objectStore.put("logs", "run.log", "password=raw-secret\nAuthorization: Bearer raw-token"
                .getBytes(StandardCharsets.UTF_8), "text/plain;charset=UTF-8");

        RunRecordEntity entity = new RunRecordEntity();
        entity.setId(2L);
        entity.setLogObjectBucket("logs");
        entity.setLogObjectKey("run.log");
        entity.setLogCharset("UTF-8");

        String content = new RunLogStorageService(properties, objectStore, null)
                .readObjectLog(entity, 1, 64 * 1024, true).getContent();

        assertTrue(content.contains("password=******"));
        assertTrue(content.contains("Authorization: ******"));
        assertFalse(content.contains("raw-secret"));
        assertFalse(content.contains("raw-token"));
    }

    @Test
    void objectLogArchiveShouldIncludeAllPersistedChunksInSequenceOrder() throws Exception {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getRunLog().setStorageType(RunLogStorageService.STORAGE_OBJECT);
        InMemoryObjectStore objectStore = new InMemoryObjectStore();
        objectStore.put("logs", "stream.0001.log.gz", "first".getBytes(StandardCharsets.UTF_8), "application/gzip");
        objectStore.put("logs", "stream.0002.log.gz", "second".getBytes(StandardCharsets.UTF_8), "application/gzip");
        RunLogChunkMapper mapper = mock(RunLogChunkMapper.class);
        RunLogChunkEntity first = chunk(1, "logs", "stream.0001.log.gz");
        RunLogChunkEntity second = chunk(2, "logs", "stream.0002.log.gz");
        when(mapper.selectList(any())).thenReturn(List.of(first, second));
        RunLogStorageService service = new RunLogStorageService(properties, objectStore, null, mapper);

        RunRecordEntity entity = new RunRecordEntity();
        entity.setId(9L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setLogObjectBucket("logs");
        entity.setLogObjectKey("stream.0002.log.gz");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.streamObjectLogArchive(entity, output);

        List<String> names = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(output.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
                bodies.add(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(names).containsExactly("0001-stream.0001.log.gz", "0002-stream.0002.log.gz");
        assertThat(bodies).containsExactly("first", "second");
    }

    @Test
    void objectLogPagingShouldJoinAndDecompressPersistedChunks() throws Exception {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getRunLog().setStorageType(RunLogStorageService.STORAGE_OBJECT);
        InMemoryObjectStore objectStore = new InMemoryObjectStore();
        objectStore.put("logs", "stream.0001.log.gz", gzip("history\n"), "application/gzip");
        objectStore.put("logs", "stream.0002.log", "tail\n".getBytes(StandardCharsets.UTF_8), "text/plain;charset=UTF-8");
        RunLogChunkMapper mapper = mock(RunLogChunkMapper.class);
        RunLogChunkEntity first = chunk(1, "logs", "stream.0001.log.gz");
        RunLogChunkEntity second = chunk(2, "logs", "stream.0002.log");
        when(mapper.selectList(any())).thenReturn(List.of(first, second));
        RunLogStorageService service = new RunLogStorageService(properties, objectStore, null, mapper);

        RunRecordEntity entity = new RunRecordEntity();
        entity.setId(10L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setLogObjectBucket("logs");
        entity.setLogObjectKey("stream.0002.log");
        entity.setLogCharset("UTF-8");

        RunLogView view = service.readObjectLog(entity, 1, 64 * 1024, true);

        assertThat(view.getContent()).isEqualTo("history\ntail\n");
        assertThat(view.getSizeBytes()).isEqualTo(13L);
        assertThat(view.getPageNo()).isEqualTo(1);
        assertThat(view.getTotalPages()).isEqualTo(1);
    }

    @Test
    void objectChunkPreviewShouldReadOnlyTheSelectedChunk() throws Exception {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getRunLog().setStorageType(RunLogStorageService.STORAGE_OBJECT);
        InMemoryObjectStore objectStore = new InMemoryObjectStore();
        objectStore.put("logs", "stream.0001.log.gz", gzip("selected\n"), "application/gzip");
        objectStore.put("logs", "stream.0002.log", "other\n".getBytes(StandardCharsets.UTF_8), "text/plain;charset=UTF-8");
        RunLogStorageService service = new RunLogStorageService(properties, objectStore, null);

        RunRecordEntity entity = new RunRecordEntity();
        entity.setId(11L);
        entity.setLogCharset("UTF-8");
        RunLogChunkEntity chunk = chunk(1, "logs", "stream.0001.log.gz");
        chunk.setRunRecordId(11L);
        chunk.setChunkStartedAt(java.time.LocalDateTime.of(2026, 8, 27, 8, 0));

        RunLogView view = service.readObjectChunk(entity, chunk, 1, 64 * 1024);

        assertThat(view.getContent()).isEqualTo("selected\n");
        assertThat(view.getContent()).doesNotContain("other");
        assertThat(view.getDownloadName()).isEqualTo("stream.0001.log.gz");
        assertThat(view.getUpdatedAt()).isEqualTo(chunk.getChunkStartedAt());
    }

    private byte[] gzip(String value) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }

    private RunLogChunkEntity chunk(int sequence, String bucket, String key) {
        RunLogChunkEntity entity = new RunLogChunkEntity();
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setRunRecordId(9L);
        entity.setSequenceNo(sequence);
        entity.setObjectBucket(bucket);
        entity.setObjectKey(key);
        return entity;
    }

    private static final class InMemoryObjectStore implements RunLogObjectStore {
        private final Map<String, byte[]> values = new ConcurrentHashMap<String, byte[]>();

        @Override
        public void put(String bucket, String objectKey, byte[] bytes, String contentType) {
            values.put(bucket + "/" + objectKey, bytes);
        }

        @Override
        public byte[] get(String bucket, String objectKey) {
            return values.get(bucket + "/" + objectKey);
        }

        @Override
        public void downloadTo(String bucket, String objectKey, OutputStream output) {
            try {
                output.write(values.get(bucket + "/" + objectKey));
            } catch (java.io.IOException failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public void delete(String bucket, String objectKey) {
            values.remove(bucket + "/" + objectKey);
        }

        @Override
        public boolean available() {
            return true;
        }
    }
}
