package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.request.UnstructuredOperationRequest;
import com.jdragon.studio.infra.entity.UnstructuredOpAuditEntity;
import com.jdragon.studio.infra.mapper.UnstructuredOpAuditMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
    private final UnstructuredManagementService service = new UnstructuredManagementService(
            dataSourceService, clusterSelectionService, projectAccess, securityService, runtimeRouter,
            null, null, auditMapper, null, null);

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
    void shouldWriteFailureAuditAndPreserveOriginalError() {
        doThrow(new StudioException(StudioErrorCode.BUSINESS_ERROR, "Target already exists"))
                .when(runtimeRouter).operate(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.operate(request()))
                .isInstanceOf(StudioException.class)
                .hasMessage("Target already exists");

        UnstructuredOpAuditEntity audit = capturedAudit();
        assertThat(audit.getStatus()).isEqualTo("FAILED");
        assertThat(audit.getMessage()).isEqualTo("Target already exists");
    }

    @Test
    void shouldCommitFailureAuditWhenOperationThrows() throws Exception {
        Method method = UnstructuredManagementService.class.getMethod(
                "operate", UnstructuredOperationRequest.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.noRollbackFor()).contains(RuntimeException.class);
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

    private UnstructuredOpAuditEntity capturedAudit() {
        ArgumentCaptor<UnstructuredOpAuditEntity> captor = ArgumentCaptor.forClass(
                UnstructuredOpAuditEntity.class);
        verify(auditMapper).insert(captor.capture());
        return captor.getValue();
    }
}
