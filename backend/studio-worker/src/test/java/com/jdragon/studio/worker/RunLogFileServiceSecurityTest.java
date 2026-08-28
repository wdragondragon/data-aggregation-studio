package com.jdragon.studio.worker;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RunLogChunkEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.infra.service.RunLogObjectStore;
import com.jdragon.studio.infra.service.RunLogStorageService;
import com.jdragon.studio.worker.runtime.log.RunLogFileService;
import com.jdragon.studio.infra.mapper.RunLogChunkMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunLogFileServiceSecurityTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(RunLogFileServiceSecurityTest.class);

    @TempDir
    Path tempDir;

    @Test
    void shouldRedactSensitiveValuesBeforeWritingTaskLogFile() throws Exception {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setRuntimeLogDir(tempDir.toString());
        RunLogFileService service = new RunLogFileService(properties,
                new RunLogStorageService(properties, new NoopObjectStore(), null));
        RunLogFileService.PreparedRunLog prepared = service.prepare(42L);

        try (RunLogFileService.RunLogScope ignored = service.openScope(prepared)) {
            LOGGER.info("password=raw-secret Authorization: Bearer raw-token");
        }

        String content = Files.readString(prepared.getAbsolutePath(), StandardCharsets.UTF_8);
        assertThat(content).contains("password=******").contains("Authorization: ******");
        assertThat(content).doesNotContain("raw-secret").doesNotContain("raw-token");
    }

    @Test
    void fullLogReadShouldExposeTheLastBoundedPage() throws Exception {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setRuntimeLogDir(tempDir.toString());
        RunLogFileService service = new RunLogFileService(properties,
                new RunLogStorageService(properties, new NoopObjectStore(), null));
        RunLogFileService.PreparedRunLog prepared = service.prepare(43L);
        byte[] content = new byte[600 * 1024];
        java.util.Arrays.fill(content, (byte) 'x');
        Files.write(prepared.getAbsolutePath(), content, StandardOpenOption.TRUNCATE_EXISTING);

        com.jdragon.studio.infra.entity.RunRecordEntity entity = new com.jdragon.studio.infra.entity.RunRecordEntity();
        entity.setId(43L);
        entity.setLogFilePath(prepared.getRelativePath());
        entity.setLogCharset(StandardCharsets.UTF_8.name());

        com.jdragon.studio.dto.model.RunLogView view = service.readFull(entity);

        assertThat(view.isTruncated()).isTrue();
        assertThat(view.isPaged()).isTrue();
        assertThat(view.getPageNo()).isEqualTo(view.getTotalPages()).isGreaterThan(1);
        assertThat(view.getPageSizeBytes()).isEqualTo(512 * 1024);
        assertThat(view.getContent()).hasSizeLessThanOrEqualTo(512 * 1024);
    }

    @Test
    void localStreamingLogSyncShouldPersistActiveChunkMetadata() throws Exception {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setRuntimeLogDir(tempDir.toString());
        RunLogChunkMapper mapper = mock(RunLogChunkMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        RunLogFileService service = new RunLogFileService(properties,
                new RunLogStorageService(properties, new NoopObjectStore(), null), mapper);
        RunLogFileService.PreparedRunLog prepared = service.prepareStreaming(
                11L, 12L, 13L, 14L, "tenant", 15L);

        try (RunLogFileService.RunLogScope ignored = service.openScope(prepared)) {
            java.nio.file.Files.writeString(prepared.getAbsolutePath(), "streaming heartbeat\n",
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            RunLogFileService.RunLogStorageResult result = service.syncActiveObjectLogs().get(14L);
            assertThat(result).isNotNull();
            assertThat(result.getStorageType()).isEqualTo(RunLogStorageService.STORAGE_LOCAL);
            assertThat(result.getChunkCount()).isEqualTo(1);
        }

        ArgumentCaptor<RunLogChunkEntity> inserted = ArgumentCaptor.forClass(RunLogChunkEntity.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getStatus()).isEqualTo("WRITING");
        assertThat(inserted.getValue().getLocalPath()).isNotBlank();
        assertThat(inserted.getValue().getSizeBytes()).isGreaterThan(0L);
    }

    @Test
    void streamingLogPagesAndArchiveShouldIncludeRolledGzipChunks() throws Exception {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setRuntimeLogDir(tempDir.toString());
        RunLogFileService service = new RunLogFileService(properties,
                new RunLogStorageService(properties, new NoopObjectStore(), null));
        RunLogFileService.PreparedRunLog prepared = service.prepareStreaming(
                21L, 22L, 23L, 24L, "tenant", 25L);
        Files.writeString(prepared.getAbsolutePath(), "tail-record\n", StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING);
        Path rolled = prepared.getAbsolutePath().resolveSibling(prepared.getAbsolutePath().getFileName()
                + ".2026-08-27-07.0.log.gz");
        try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(rolled))) {
            output.write("history-record\n".getBytes(StandardCharsets.UTF_8));
        }
        Files.setLastModifiedTime(rolled, FileTime.fromMillis(
                Files.getLastModifiedTime(prepared.getAbsolutePath()).toMillis() - 1000L));

        com.jdragon.studio.infra.entity.RunRecordEntity entity = new com.jdragon.studio.infra.entity.RunRecordEntity();
        entity.setId(24L);
        entity.setLogFilePath(prepared.getRelativePath());
        entity.setLogCharset(StandardCharsets.UTF_8.name());

        com.jdragon.studio.dto.model.RunLogView full = service.readFull(entity);
        assertThat(full.getContent()).contains("history-record", "tail-record");
        assertThat(full.getSizeBytes()).isEqualTo((long) "history-record\ntail-record\n".getBytes(StandardCharsets.UTF_8).length);

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        service.writeArchive(entity, archive);
        List<String> names = new ArrayList<String>();
        List<byte[]> entries = new ArrayList<byte[]>();
        try (ZipInputStream input = new ZipInputStream(new java.io.ByteArrayInputStream(archive.toByteArray()))) {
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                names.add(entry.getName());
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                input.transferTo(bytes);
                entries.add(bytes.toByteArray());
            }
        }
        assertThat(names).containsExactlyInAnyOrder(
                prepared.getAbsolutePath().getFileName().toString(), rolled.getFileName().toString());
        assertThat(entries).anySatisfy(bytes -> assertThat(bytes).contains("tail-record".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void chunkPreviewShouldReadOnlyTheSelectedGzipChunk() throws Exception {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setRuntimeLogDir(tempDir.toString());
        RunLogFileService service = new RunLogFileService(properties,
                new RunLogStorageService(properties, new NoopObjectStore(), null));
        RunLogFileService.PreparedRunLog prepared = service.prepareStreaming(
                41L, 42L, 43L, 44L, "tenant", 45L);
        Files.writeString(prepared.getAbsolutePath(), "active-record\n", StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING);
        Path rolled = prepared.getAbsolutePath().resolveSibling(prepared.getAbsolutePath().getFileName()
                + ".2026-08-27-08.0.log.gz");
        try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(rolled))) {
            output.write("selected-record\n".getBytes(StandardCharsets.UTF_8));
        }
        RunLogChunkEntity chunk = new RunLogChunkEntity();
        chunk.setId(401L);
        chunk.setRunRecordId(44L);
        chunk.setLocalPath(prepared.getAbsolutePath().getParent().getFileName() + "/" + rolled.getFileName());
        chunk.setObjectKey(rolled.getFileName().toString());
        chunk.setChunkStartedAt(java.time.LocalDateTime.now());

        RunRecordEntity entity = new RunRecordEntity();
        entity.setId(44L);
        entity.setLogFilePath(prepared.getRelativePath());
        entity.setLogCharset(StandardCharsets.UTF_8.name());

        RunLogView view = service.readChunkPage(entity, chunk, 1, 4096);

        assertThat(view.getContent()).contains("selected-record");
        assertThat(view.getContent()).doesNotContain("active-record");
        assertThat(view.getDownloadName()).isEqualTo(rolled.getFileName().toString());
    }

    @Test
    void streamingAppenderShouldRollWhenActiveChunkExceeds128Mb() throws Exception {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setRuntimeLogDir(tempDir.toString());
        RunLogFileService service = new RunLogFileService(properties,
                new RunLogStorageService(properties, new NoopObjectStore(), null));
        RunLogFileService.PreparedRunLog prepared = service.prepareStreaming(
                31L, 32L, 33L, 34L, "tenant", 35L);
        try (RandomAccessFile file = new RandomAccessFile(prepared.getAbsolutePath().toFile(), "rw")) {
            file.setLength(128L * 1024L * 1024L + 1L);
        }

        try (RunLogFileService.RunLogScope ignored = service.openScope(prepared)) {
            LOGGER.info("trigger streaming log rollover");
        }

        List<Path> rolledChunks;
        try (java.util.stream.Stream<Path> files = Files.list(prepared.getAbsolutePath().getParent())) {
            rolledChunks = files
                    .filter(path -> path.getFileName().toString().startsWith(
                            prepared.getAbsolutePath().getFileName().toString() + "."))
                    .filter(path -> path.getFileName().toString().endsWith(".log.gz"))
                    .toList();
        }
        assertThat(rolledChunks).hasSize(1);
        assertThat(Files.size(prepared.getAbsolutePath())).isLessThan(128L * 1024L * 1024L);
    }

    private static final class NoopObjectStore implements RunLogObjectStore {
        @Override
        public void put(String bucket, String objectKey, byte[] bytes, String contentType) {
        }

        @Override
        public byte[] get(String bucket, String objectKey) {
            return new byte[0];
        }

        @Override
        public void delete(String bucket, String objectKey) {
        }

        @Override
        public boolean available() {
            return true;
        }
    }
}
