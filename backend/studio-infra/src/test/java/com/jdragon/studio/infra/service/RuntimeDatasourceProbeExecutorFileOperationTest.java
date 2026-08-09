package com.jdragon.studio.infra.service;

import com.jdragon.aggregation.datasource.file.transfer.TransferFileEntry;
import com.jdragon.aggregation.datasource.file.transfer.TransferFilePage;
import com.jdragon.aggregation.datasource.file.transfer.TransferFileSystem;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        when(fileSystem.listPage("/folder", null, 1)).thenReturn(
                new TransferFilePage(List.of(file("/folder/item.txt")), null, false));

        assertThrows(IllegalStateException.class,
                () -> executor.operate(datasource, "DELETE", "/folder",
                        null, false));

        verify(fileSystem).stat("/folder");
        verify(fileSystem, never()).delete("/folder");
    }

    @Test
    void recursiveDeleteRemovesNonEmptyDirectory() throws Exception {
        when(fileSystem.stat("/folder")).thenReturn(directory("/folder"));
        when(fileSystem.listPage("/folder", null, 1)).thenReturn(
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

    private TransferFileEntry file(String path) {
        return new TransferFileEntry(path, path.substring(path.lastIndexOf('/') + 1),
                false, 10L, 1L, "etag");
    }

    private TransferFileEntry directory(String path) {
        return new TransferFileEntry(path, path.substring(path.lastIndexOf('/') + 1),
                true, 0L, 1L, null);
    }
}
