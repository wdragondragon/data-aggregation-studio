package com.jdragon.studio.infra.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jdragon.aggregation.datasource.file.transfer.TransferFileEntry;
import com.jdragon.aggregation.datasource.file.transfer.TransferFilePage;
import com.jdragon.aggregation.datasource.file.transfer.TransferFileSystem;
import com.jdragon.aggregation.datasource.file.transfer.StorageCapabilities;
import com.jdragon.aggregation.datasource.file.transfer.TransferWriteSession;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class RuntimeDatasourceProbeExecutorFileOperationTest {

    private final AggregationSourceCapabilityProvider provider =
            mock(AggregationSourceCapabilityProvider.class);
    private final TransferFileSystem fileSystem = mock(TransferFileSystem.class);
    private final DataSourceDefinition datasource = new DataSourceDefinition();
    private RuntimeDatasourceProbeExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        when(provider.openTransferFileSystem(datasource)).thenReturn(fileSystem);
        executor = new RuntimeDatasourceProbeExecutor(
                provider, mock(DataDevelopmentSqlExecutor.class));
    }

    @Test
    void createDirectoryDoesNotStatMissingSourcePath() throws Exception {
        when(fileSystem.transferExists("/new-directory")).thenReturn(false);

        executor.operate(datasource, "CREATE_DIRECTORY", "new-directory", null, false);

        verify(fileSystem).transferExists("/new-directory");
        verify(fileSystem).mkdir("/new-directory");
        verify(fileSystem, never()).stat(anyString());
    }

    @Test
    void browseExposesTheRemoteInitialWorkingDirectoryWithoutRewritingTheRequestedPath() throws Exception {
        when(fileSystem.listPage("/", null, 200)).thenReturn(
                new TransferFilePage(List.of(directory("/upload")), null, false));
        when(fileSystem.initialPath()).thenReturn("/upload");
        when(fileSystem.capabilities()).thenReturn(
                new StorageCapabilities(true, true, true, false, false, false, Set.of("SHA-256"), true));

        var result = executor.browse(datasource, "/", null, 200);

        assertEquals("/", result.getPath());
        assertEquals("/upload", result.getInitialPath());
        assertEquals("/upload", result.getEntries().get(0).getPath());
    }

    @Test
    void renameStatsSourceAndMovesWithinSameParent() throws Exception {
        TransferFileEntry source = file("/folder/source.txt");
        when(fileSystem.transferExists("/folder/renamed.txt")).thenReturn(false);
        when(fileSystem.stat("/folder/source.txt")).thenReturn(source);

        executor.operate(datasource, "RENAME", "/folder/source.txt",
                "/folder/renamed.txt", false);

        verify(fileSystem).stat("/folder/source.txt");
        verify(fileSystem).move("/folder/source.txt", "/folder/renamed.txt", false);
    }

    @Test
    void moveStatsSourceAndRejectsExistingTarget() throws Exception {
        when(fileSystem.transferExists("/target/source.txt")).thenReturn(true);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> executor.operate(datasource, "MOVE", "/source.txt",
                        "/target/source.txt", false));

        assertInstanceOf(FileAlreadyExistsException.class, failure.getCause());
        verify(fileSystem, never()).stat("/source.txt");
        verify(fileSystem, never()).move(anyString(), anyString(), anyBoolean());
    }

    @Test
    void deleteStatsSourceAndRequiresConfirmationForNonEmptyDirectory() throws Exception {
        when(fileSystem.stat("/folder")).thenReturn(directory("/folder"));
        when(fileSystem.listPage("/folder", null, 1_000)).thenReturn(
                new TransferFilePage(List.of(file("/folder/item.txt")), null, false));

        assertThrows(IllegalStateException.class,
                () -> executor.operate(datasource, "DELETE", "/folder",
                        null, false));

        verify(fileSystem).stat("/folder");
        verify(fileSystem, never()).delete("/folder");
    }

    @Test
    void deleteRejectsTruncatedPageEvenWhenDirectoryMarkerWasFiltered() throws Exception {
        when(fileSystem.stat("/folder")).thenReturn(directory("/folder"));
        when(fileSystem.listPage("/folder", null, 1_000)).thenReturn(
                new TransferFilePage(List.of(), "next-marker", true));

        assertThrows(IllegalStateException.class,
                () -> executor.operate(datasource, "DELETE", "/folder",
                        null, false));

        verify(fileSystem).listPage("/folder", null, 1_000);
        verify(fileSystem, never()).delete("/folder");
    }

    @Test
    void recursiveDeleteRemovesNonEmptyDirectory() throws Exception {
        when(fileSystem.stat("/folder")).thenReturn(directory("/folder"));
        when(fileSystem.listPage("/folder", null, 1_000)).thenReturn(
                new TransferFilePage(List.of(file("/folder/item.txt")), null, false));

        executor.operate(datasource, "DELETE", "/folder", null, true);

        verify(fileSystem).delete("/folder");
    }

    @Test
    void rootDeleteIsRejectedBeforeStat() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> executor.operate(datasource, "DELETE", "/", null, true));

        verify(fileSystem, never()).stat(anyString());
        verify(fileSystem, never()).delete(anyString());
    }

    @Test
    void pathTraversalIsRejectedBeforeOpeningFilesystem() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> executor.operate(datasource, "DELETE", "/folder/../secret",
                        null, true));

        verify(provider, never()).openTransferFileSystem(datasource);
    }

    @Test
    void downloadRejectsUnexpectedEndOfFile() throws Exception {
        when(fileSystem.stat("/file.bin")).thenReturn(file("/file.bin"));
        when(fileSystem.openRead("/file.bin", 0L, 10L))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> executor.download(datasource, "/file.bin", new ByteArrayOutputStream()));

        assertInstanceOf(java.io.IOException.class, failure.getCause());
    }

    @Test
    void uploadStreamsExpectedLengthAndCommitsTemporaryFile() throws Exception {
        byte[] content = "streamed upload".getBytes(StandardCharsets.UTF_8);
        TransferWriteSession session = new TransferWriteSession(
                "upload-1", "/.target.txt.part", 0L, Map.of());
        when(fileSystem.transferExists("/target.txt")).thenReturn(false);
        when(fileSystem.prepareWrite(anyString(), anyString())).thenReturn(session);
        when(fileSystem.append(org.mockito.ArgumentMatchers.eq(session),
                org.mockito.ArgumentMatchers.eq(0L), any(),
                org.mockito.ArgumentMatchers.eq((long) content.length)))
                .thenAnswer(invocation -> {
                    byte[] received = invocation.<java.io.InputStream>getArgument(2).readAllBytes();
                    assertEquals("streamed upload", new String(received, StandardCharsets.UTF_8));
                    return (long) received.length;
                });

        long bytes = executor.upload(datasource, "/target.txt", false,
                content.length, new ByteArrayInputStream(content));

        assertEquals(content.length, bytes);
        verify(fileSystem).commit(session, "/target.txt", false);
    }

    @Test
    void uploadRejectsConflictAndDoesNotCreateTemporaryFile() throws Exception {
        when(fileSystem.transferExists("/target.txt")).thenReturn(true);
        when(fileSystem.stat("/target.txt")).thenReturn(file("/target.txt", 10L));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> executor.upload(datasource, "/target.txt", false, 1L,
                        new ByteArrayInputStream(new byte[]{1})));

        assertInstanceOf(FileAlreadyExistsException.class, failure.getCause());
        verify(fileSystem, never()).prepareWrite(anyString(), anyString());
    }

    @Test
    void expectedUploadConflictUsesWarningInsteadOfWorkerFailureError() throws Exception {
        when(fileSystem.transferExists("/target.txt")).thenReturn(true);
        when(fileSystem.stat("/target.txt")).thenReturn(file("/target.txt", 10L));
        Logger logger = (Logger) LoggerFactory.getLogger(RuntimeDatasourceProbeExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThrows(IllegalStateException.class,
                    () -> executor.upload(datasource, "/target.txt", false, 1L,
                            new ByteArrayInputStream(new byte[]{1})));

            org.assertj.core.api.Assertions.assertThat(appender.list)
                    .anySatisfy(event -> {
                        org.assertj.core.api.Assertions.assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        org.assertj.core.api.Assertions.assertThat(event.getFormattedMessage())
                                .contains("[UF_OPERATION_REJECTED]");
                    })
                    .noneSatisfy(event -> org.assertj.core.api.Assertions.assertThat(event.getFormattedMessage())
                            .contains("[UF_WORKER_FAILED]"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void permissionDeniedUsesSanitizedSingleLineWarningAndSafeEnvelope() throws Exception {
        when(fileSystem.transferExists("/denied")).thenThrow(new java.io.IOException(
                "Permission denied token=plain-secret\r\n"
                        + "\tat example.sftp.Plugin.mkdir(Plugin.java:42)"));
        Logger logger = (Logger) LoggerFactory.getLogger(RuntimeDatasourceProbeExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> executor.operate(datasource, "CREATE_DIRECTORY", "/denied", null, false));

            org.assertj.core.api.Assertions.assertThat(failure.getMessage())
                    .isEqualTo("File operation failed: Permission denied token=******")
                    .doesNotContain("plain-secret", "Plugin.java", "\r", "\n");
            org.assertj.core.api.Assertions.assertThat(appender.list)
                    .anySatisfy(event -> {
                        org.assertj.core.api.Assertions.assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        org.assertj.core.api.Assertions.assertThat(event.getFormattedMessage())
                                .contains("[UF_OPERATION_REJECTED]")
                                .contains("message=Permission denied token=******")
                                .doesNotContain("plain-secret", "Plugin.java", "\r", "\n");
                    })
                    .noneSatisfy(event -> org.assertj.core.api.Assertions.assertThat(event.getFormattedMessage())
                            .contains("[UF_WORKER_FAILED]"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void unexpectedFailureStillUsesErrorWithThrowable() throws Exception {
        java.io.IOException transportFailure = new java.io.IOException("Connection reset");
        when(fileSystem.transferExists("/failed")).thenThrow(transportFailure);
        Logger logger = (Logger) LoggerFactory.getLogger(RuntimeDatasourceProbeExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThrows(IllegalStateException.class,
                    () -> executor.operate(datasource, "CREATE_DIRECTORY", "/failed", null, false));

            org.assertj.core.api.Assertions.assertThat(appender.list)
                    .anySatisfy(event -> {
                        org.assertj.core.api.Assertions.assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                        org.assertj.core.api.Assertions.assertThat(event.getFormattedMessage())
                                .contains("[UF_WORKER_FAILED]")
                                .contains("message=Connection reset");
                        org.assertj.core.api.Assertions.assertThat(event.getThrowableProxy()).isNotNull();
                    });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void uploadAbortsTemporaryFileWhenSourceEndsEarly() throws Exception {
        TransferWriteSession session = new TransferWriteSession(
                "upload-2", "/.target.txt.part", 0L, Map.of());
        when(fileSystem.transferExists("/target.txt")).thenReturn(false);
        when(fileSystem.prepareWrite(anyString(), anyString())).thenReturn(session);
        when(fileSystem.append(org.mockito.ArgumentMatchers.eq(session),
                org.mockito.ArgumentMatchers.eq(0L), any(),
                org.mockito.ArgumentMatchers.eq(5L))).thenReturn(2L);
        when(provider.openTransferFileSystem(datasource)).thenReturn(fileSystem, fileSystem);

        assertThrows(IllegalStateException.class,
                () -> executor.upload(datasource, "/target.txt", false, 5L,
                        new ByteArrayInputStream(new byte[]{1, 2})));

        verify(fileSystem).abort(session);
        verify(fileSystem, never()).commit(any(), anyString(), anyBoolean());
    }

    @Test
    void archiveRecursesPagedDirectoriesPreservesEmptyDirectoriesAndDeduplicatesChildren()
            throws Exception {
        TransferFileEntry reports = directory("/reports");
        TransferFileEntry empty = directory("/reports/empty");
        TransferFileEntry first = file("/reports/a.txt", 1L);
        TransferFileEntry second = file("/reports/nested/b.txt", 1L);
        TransferFileEntry loose = file("/loose.txt", 1L);
        when(fileSystem.stat("/reports")).thenReturn(reports);
        when(fileSystem.stat("/reports/a.txt")).thenReturn(first);
        when(fileSystem.stat("/loose.txt")).thenReturn(loose);
        when(fileSystem.listPage("/reports", null, 500)).thenReturn(
                new TransferFilePage(List.of(empty, first), "next", true));
        when(fileSystem.listPage("/reports", "next", 500)).thenReturn(
                new TransferFilePage(List.of(directory("/reports/nested")), null, false));
        when(fileSystem.listPage("/reports/empty", null, 500)).thenReturn(
                new TransferFilePage(List.of(), null, false));
        when(fileSystem.listPage("/reports/nested", null, 500)).thenReturn(
                new TransferFilePage(List.of(second), null, false));
        when(fileSystem.openRead("/reports/a.txt", 0L, 1L))
                .thenReturn(new ByteArrayInputStream(new byte[]{'a'}));
        when(fileSystem.openRead("/reports/nested/b.txt", 0L, 1L))
                .thenReturn(new ByteArrayInputStream(new byte[]{'b'}));
        when(fileSystem.openRead("/loose.txt", 0L, 1L))
                .thenReturn(new ByteArrayInputStream(new byte[]{'l'}));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        executor.downloadArchive(datasource,
                List.of("/reports", "/reports/a.txt", "/loose.txt"), output);

        Map<String, String> archive = unzip(output.toByteArray());
        assertEquals(Set.of("reports/", "reports/empty/", "reports/a.txt",
                        "reports/nested/", "reports/nested/b.txt", "loose.txt"),
                archive.keySet());
        assertEquals("a", archive.get("reports/a.txt"));
        assertEquals("b", archive.get("reports/nested/b.txt"));
        assertEquals("l", archive.get("loose.txt"));
        verify(fileSystem, org.mockito.Mockito.times(1))
                .openRead("/reports/a.txt", 0L, 1L);
    }

    private Map<String, String> unzip(byte[] bytes) throws Exception {
        Map<String, String> result = new LinkedHashMap<String, String>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes),
                StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                result.put(entry.getName(), entry.isDirectory() ? ""
                        : new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private TransferFileEntry file(String path) {
        return file(path, 10L);
    }

    private TransferFileEntry file(String path, long size) {
        return new TransferFileEntry(path, path.substring(path.lastIndexOf('/') + 1),
                false, size, 1L, "etag");
    }

    private TransferFileEntry directory(String path) {
        return new TransferFileEntry(path, path.substring(path.lastIndexOf('/') + 1),
                true, 0L, 1L, null);
    }
}
