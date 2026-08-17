package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.FileTransferFileEntryView;
import com.jdragon.studio.dto.model.request.UnstructuredOperationRequest;
import com.jdragon.studio.infra.entity.UnstructuredOpAuditEntity;
import com.jdragon.studio.infra.mapper.UnstructuredOpAuditMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnstructuredManagementOperationAuditTest {

    private final DataSourceService dataSourceService = mock(DataSourceService.class);
    private final RuntimeClusterSelectionService clusterSelectionService = mock(RuntimeClusterSelectionService.class);
    private final ProjectResourceAccessService projectAccess = mock(ProjectResourceAccessService.class);
    private final StudioSecurityService securityService = mock(StudioSecurityService.class);
    private final RuntimeDatasourceProbeRouter runtimeRouter = mock(RuntimeDatasourceProbeRouter.class);
    private final UnstructuredOpAuditMapper auditMapper = mock(UnstructuredOpAuditMapper.class);
    private final DatasourceTypeCapabilityService capabilityService =
            mock(DatasourceTypeCapabilityService.class);
    private final UnstructuredManagementService service = new UnstructuredManagementService(
            dataSourceService, clusterSelectionService, projectAccess, securityService, runtimeRouter,
            null, null, auditMapper, null, null, capabilityService);

    @BeforeEach
    void setUp() {
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(101L);
        datasource.setTenantId("default");
        datasource.setProjectId(10L);
        datasource.setCreatedBy(20L);
        datasource.setTypeCode("ftp");
        when(dataSourceService.requireRunnableForExecution(101L)).thenReturn(datasource);
        when(projectAccess.requireCurrentProjectId()).thenReturn(10L);
        when(securityService.currentUserId()).thenReturn(20L);
        when(securityService.currentUsername()).thenReturn("admin");
    }

    @Test
    void shouldWriteSuccessAuditForCompletedOperation() {
        service.operate(request());

        UnstructuredOpAuditEntity audit = capturedAudit();
        assertThat(audit.getStatus()).isEqualTo("SUCCESS");
        assertThat(audit.getOperation()).isEqualTo("CREATE_DIRECTORY");
        assertThat(audit.getSourcePath()).isEqualTo("/acceptance");
        assertThat(audit.getRuntimeClusterId()).isEqualTo(11L);
        assertThat(audit.getUsername()).isEqualTo("admin");
    }

    @Test
    void shouldWriteSuccessAuditForUpload() {
        doThrow(new StudioException(StudioErrorCode.NOT_FOUND, "missing"))
                .when(runtimeRouter).stat(any(), any(), any(), any());
        when(runtimeRouter.upload(any(), any(), any(), anyBoolean(), anyLong(), any(), any()))
                .thenReturn(7L);

        service.upload(11L, 101L, "/incoming/a.txt", false, 7L,
                new ByteArrayInputStream("content".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        UnstructuredOpAuditEntity audit = capturedAudit();
        assertThat(audit.getStatus()).isEqualTo("SUCCESS");
        assertThat(audit.getOperation()).isEqualTo("UPLOAD");
        assertThat(audit.getTargetPath()).isEqualTo("/incoming/a.txt");
        assertThat(audit.getMessage()).isEqualTo("7 bytes");
    }

    @Test
    void shouldWriteSuccessAuditForArchiveDownload() {
        UnstructuredManagementService.PreparedArchive prepared =
                new UnstructuredManagementService.PreparedArchive(
                        datasource(), 11L, List.of("/reports", "/readme.txt"),
                        "download.zip", 20L, "admin");

        service.downloadArchive(prepared, new ByteArrayOutputStream());

        UnstructuredOpAuditEntity audit = capturedAudit();
        assertThat(audit.getStatus()).isEqualTo("SUCCESS");
        assertThat(audit.getOperation()).isEqualTo("DOWNLOAD_ARCHIVE");
        assertThat(audit.getSourcePath()).isEqualTo("/reports\n/readme.txt");
        assertThat(audit.getRecursive()).isEqualTo(1);
    }

    @Test
    void shouldPrepareSingleFileForNativeDownload() {
        FileTransferFileEntryView entry = new FileTransferFileEntryView();
        entry.setName("report.txt");
        entry.setSize(42L);
        entry.setDirectory(false);
        when(runtimeRouter.stat(any(), any(), any(), any())).thenReturn(entry);

        UnstructuredManagementService.PreparedNativeDownload prepared =
                service.prepareNativeDownload(11L, 101L,
                        List.of("reports/./report.txt", "/reports/report.txt"));

        assertThat(prepared.archive()).isFalse();
        assertThat(prepared.fileName()).isEqualTo("report.txt");
        assertThat(prepared.contentLength()).isEqualTo(42L);
        assertThat(prepared.paths()).containsExactly("/reports/report.txt");
        assertThat(prepared.download().path()).isEqualTo("/reports/report.txt");
    }

    @Test
    void shouldPrepareDirectoryOrMultipleSelectionsAsArchive() {
        FileTransferFileEntryView directory = new FileTransferFileEntryView();
        directory.setName("reports");
        directory.setDirectory(true);
        when(runtimeRouter.stat(any(), any(), any(), any())).thenReturn(directory);

        UnstructuredManagementService.PreparedNativeDownload directoryDownload =
                service.prepareNativeDownload(11L, 101L, List.of("/reports"));

        assertThat(directoryDownload.archive()).isTrue();
        assertThat(directoryDownload.fileName()).isEqualTo("reports.zip");
        assertThat(directoryDownload.contentLength()).isNull();

        FileTransferFileEntryView file = new FileTransferFileEntryView();
        file.setName("a.txt");
        file.setDirectory(false);
        when(runtimeRouter.stat(any(), any(), any(), any())).thenReturn(file);
        UnstructuredManagementService.PreparedNativeDownload multiple =
                service.prepareNativeDownload(11L, 101L,
                        List.of("/a.txt", "/b.txt"));

        assertThat(multiple.archive()).isTrue();
        assertThat(multiple.fileName()).isEqualTo("download.zip");
        assertThat(multiple.paths()).containsExactly("/a.txt", "/b.txt");
    }

    @Test
    void shouldWriteFailureAuditAndPreserveOriginalError() {
        doThrow(new StudioException(StudioErrorCode.BUSINESS_ERROR, "Target already exists"))
                .when(runtimeRouter).operate(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.operate(request()))
                .isInstanceOf(StudioException.class)
                .hasMessage("Target already exists");

        UnstructuredOpAuditEntity audit = capturedAudit();
        assertThat(audit.getStatus()).isEqualTo("FAILED");
        assertThat(audit.getMessage()).isEqualTo("Target already exists");
    }

    @Test
    void shouldTruncateLongFailureAuditWithoutReplacingTheOriginalError() {
        String originalMessage = "x".repeat(3000);
        doThrow(new StudioException(StudioErrorCode.BUSINESS_ERROR, originalMessage))
                .when(runtimeRouter).operate(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.operate(request()))
                .isInstanceOf(StudioException.class)
                .hasMessage(originalMessage);

        UnstructuredOpAuditEntity audit = capturedAudit();
        assertThat(audit.getStatus()).isEqualTo("FAILED");
        assertThat(audit.getMessage()).hasSize(1800).endsWith(" ...[truncated]");
    }

    @Test
    void shouldPreserveOperationResultWhenAuditPersistenceFails() {
        doThrow(new RuntimeException("audit database unavailable"))
                .when(auditMapper).insert(any(UnstructuredOpAuditEntity.class));

        assertThat(service.operate(request()).getMessage()).isEqualTo("Operation completed");

        StudioException operationFailure = new StudioException(
                StudioErrorCode.BUSINESS_ERROR, "SFTP permission denied");
        doThrow(operationFailure)
                .when(runtimeRouter).operate(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.operate(request()))
                .isSameAs(operationFailure);
    }

    @Test
    void shouldCommitFailureAuditWhenOperationThrows() throws Exception {
        Method method = UnstructuredManagementService.class.getMethod(
                "operate", UnstructuredOperationRequest.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.noRollbackFor()).contains(RuntimeException.class);
    }

    @Test
    void shouldSanitizeAndBoundUnexpectedFailureStackTrace() {
        String secret = "token=plain-secret";
        IllegalStateException failure = new IllegalStateException(
                secret + " " + "x".repeat(20_000));

        String stackTrace = UnstructuredManagementService.sanitizedStackTrace(failure);

        assertThat(stackTrace).doesNotContain("plain-secret");
        assertThat(stackTrace).contains("token=******");
        assertThat(stackTrace).hasSizeLessThanOrEqualTo(12 * 1024);
        assertThat(stackTrace).endsWith("...[truncated]");

        String message = UnstructuredManagementService.sanitizedErrorMessage(
                secret + " " + "x".repeat(3_000));
        assertThat(message).doesNotContain("plain-secret");
        assertThat(message).hasSizeLessThanOrEqualTo(2 * 1024);
        assertThat(message).endsWith(" ...[truncated]");

        String multiline = UnstructuredManagementService.sanitizedErrorMessage(
                "Permission denied token=plain-secret\r\n\tat example.Plugin.mkdir(Plugin.java:42)");
        assertThat(multiline)
                .isEqualTo("Permission denied token=******")
                .doesNotContain("plain-secret", "Plugin.java", "\r", "\n");
    }

    private UnstructuredOperationRequest request() {
        UnstructuredOperationRequest request = new UnstructuredOperationRequest();
        request.setRuntimeClusterId(11L);
        request.setDatasourceId(101L);
        request.setOperation("CREATE_DIRECTORY");
        request.setSourcePath("/acceptance");
        request.setRecursiveConfirmed(false);
        return request;
    }

    private DataSourceDefinition datasource() {
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(101L);
        datasource.setTenantId("default");
        datasource.setProjectId(10L);
        datasource.setCreatedBy(20L);
        datasource.setTypeCode("ftp");
        return datasource;
    }

    private UnstructuredOpAuditEntity capturedAudit() {
        ArgumentCaptor<UnstructuredOpAuditEntity> captor = ArgumentCaptor.forClass(
                UnstructuredOpAuditEntity.class);
        verify(auditMapper).insert(captor.capture());
        return captor.getValue();
    }
}
