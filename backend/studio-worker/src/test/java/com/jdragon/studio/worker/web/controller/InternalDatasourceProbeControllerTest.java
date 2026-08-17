package com.jdragon.studio.worker.web.controller;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.enums.RuntimeDatasourceProbeMode;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.dto.ConnectionTestResult;
import com.jdragon.studio.dto.model.request.RuntimeDatasourceProbeRequest;
import com.jdragon.studio.dto.model.request.RuntimeDatasourceUploadRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.mapper.DatasourceClusterBindingMapper;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.security.StudioRequestContext;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.RuntimeDatasourceProbeExecutor;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import com.jdragon.studio.worker.unstructured.UnstructuredFileExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalDatasourceProbeControllerTest {

    @AfterEach
    void clearContext() {
        StudioRequestContextHolder.clear();
        MDC.clear();
    }

    @Test
    void shouldBindAndRestoreOperationIdAroundWorkerFileOperation() {
        Fixture fixture = fixture();
        RuntimeDatasourceProbeRequest request = storedRequest();
        request.setFileOperation("CREATE_DIRECTORY");
        request.setOperationPath("/new-directory");
        DataSourceDefinition canonical = datasource(301L, "tenant-a", 10L);
        when(fixture.dataSourceService.getInternal(301L)).thenReturn(canonical);
        when(fixture.bindingMapper.selectCount(any())).thenReturn(1L);
        doAnswer(invocation -> {
            assertThat(MDC.get("operationId")).isEqualTo("operation-123");
            return null;
        }).when(fixture.executor).operate(canonical, "CREATE_DIRECTORY",
                "/new-directory", null, null);
        MDC.put("operationId", "previous-operation");

        Result<Void> result = fixture.controller.fileOperation(
                "internal-secret", "operation-123", request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(MDC.get("operationId")).isEqualTo("previous-operation");
    }

    @Test
    void shouldIgnoreUnsafeOperationIdAndRestoreWorkerMdc() {
        Fixture fixture = fixture();
        RuntimeDatasourceProbeRequest request = storedRequest();
        request.setFileOperation("CREATE_DIRECTORY");
        request.setOperationPath("/new-directory");
        DataSourceDefinition canonical = datasource(301L, "tenant-a", 10L);
        when(fixture.dataSourceService.getInternal(301L)).thenReturn(canonical);
        when(fixture.bindingMapper.selectCount(any())).thenReturn(1L);
        doAnswer(invocation -> {
            assertThat(MDC.get("operationId")).isNull();
            return null;
        }).when(fixture.executor).operate(canonical, "CREATE_DIRECTORY",
                "/new-directory", null, null);
        MDC.put("operationId", "previous-operation");

        Result<Void> result = fixture.controller.fileOperation(
                "internal-secret", "invalid\r\noperation", request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(MDC.get("operationId")).isEqualTo("previous-operation");
    }

    @Test
    void shouldUseCanonicalStoredDatasourceAndRestorePreviousContext() {
        Fixture fixture = fixture();
        RuntimeDatasourceProbeRequest request = storedRequest();
        DataSourceDefinition canonical = datasource(301L, "tenant-a", 10L);
        when(fixture.dataSourceService.getInternal(301L)).thenReturn(canonical);
        when(fixture.bindingMapper.selectCount(any())).thenReturn(1L);
        ConnectionTestResult expected = new ConnectionTestResult();
        expected.setSuccess(true);
        when(fixture.executor.test(canonical)).thenAnswer(invocation -> {
            StudioRequestContext context = StudioRequestContextHolder.getContext();
            assertThat(context.getTenantId()).isEqualTo("tenant-a");
            assertThat(context.getProjectId()).isEqualTo(20L);
            assertThat(context.getUserId()).isEqualTo(99L);
            return expected;
        });
        StudioRequestContext previous = new StudioRequestContext();
        previous.setTenantId("previous-tenant");
        StudioRequestContextHolder.setContext(previous);

        assertThat(fixture.controller.probe("internal-secret", request).getData()).isSameAs(expected);
        assertThat(StudioRequestContextHolder.getContext()).isSameAs(previous);
    }

    @Test
    void shouldRejectStoredDatasourceWhenBindingWasRevoked() {
        Fixture fixture = fixture();
        RuntimeDatasourceProbeRequest request = storedRequest();
        when(fixture.dataSourceService.getInternal(301L))
                .thenReturn(datasource(301L, "tenant-a", 10L));
        when(fixture.bindingMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> fixture.controller.probe("internal-secret", request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(fixture.executor, never()).test(any());
        assertThat(StudioRequestContextHolder.getContext()).isNull();
    }

    @Test
    void shouldAllowDraftOnlyForConnectionProbe() {
        Fixture fixture = fixture();
        RuntimeDatasourceProbeRequest request = baseRequest(RuntimeDatasourceProbeMode.DRAFT_FORM);
        DataSourceDefinition draft = datasource(null, "tenant-a", 20L);
        draft.setApplicableClusterIds(List.of(50L));
        request.setDatasource(draft);
        when(fixture.executor.test(draft)).thenReturn(new ConnectionTestResult());

        fixture.controller.probe("internal-secret", request);

        assertThatThrownBy(() -> fixture.controller.discover("internal-secret", request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(fixture.executor).test(draft);
        verify(fixture.executor, never()).discover(any(), any(), any(), any());
    }

    @Test
    void shouldClearContextWhenExecutorFails() {
        Fixture fixture = fixture();
        RuntimeDatasourceProbeRequest request = storedRequest();
        DataSourceDefinition canonical = datasource(301L, "tenant-a", 10L);
        when(fixture.dataSourceService.getInternal(301L)).thenReturn(canonical);
        when(fixture.bindingMapper.selectCount(any())).thenReturn(1L);
        when(fixture.executor.test(canonical)).thenThrow(new IllegalStateException("probe failed"));

        assertThatThrownBy(() -> fixture.controller.probe("internal-secret", request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("probe failed");
        assertThat(StudioRequestContextHolder.getContext()).isNull();
    }

    @Test
    void shouldReturnStudioBusinessErrorEnvelopeForControlPlanePreservation() {
        Fixture fixture = fixture();
        RuntimeDatasourceProbeRequest request = storedRequest();
        DataSourceDefinition canonical = datasource(301L, "tenant-a", 10L);
        when(fixture.dataSourceService.getInternal(301L)).thenReturn(canonical);
        when(fixture.bindingMapper.selectCount(any())).thenReturn(1L);
        when(fixture.executor.test(canonical)).thenThrow(
                new StudioException(StudioErrorCode.BAD_REQUEST, "Datasource configuration is invalid"));

        Result<ConnectionTestResult> result = fixture.controller.probe("internal-secret", request);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(StudioErrorCode.BAD_REQUEST);
        assertThat(result.getMessage()).isEqualTo("Datasource configuration is invalid");
        assertThat(StudioRequestContextHolder.getContext()).isNull();
    }

    @Test
    void shouldReturnFileOperationFailureAsAuthenticatedBusinessEnvelope() {
        Fixture fixture = fixture();
        RuntimeDatasourceProbeRequest request = storedRequest();
        request.setFileOperation("CREATE_DIRECTORY");
        request.setOperationPath("/existing");
        DataSourceDefinition canonical = datasource(301L, "tenant-a", 10L);
        when(fixture.dataSourceService.getInternal(301L)).thenReturn(canonical);
        when(fixture.bindingMapper.selectCount(any())).thenReturn(1L);
        doThrow(new IllegalStateException("File operation failed: /existing"))
                .when(fixture.executor).operate(canonical, "CREATE_DIRECTORY",
                        "/existing", null, null);

        Result<Void> result = fixture.controller.fileOperation("internal-secret", request);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(StudioErrorCode.BUSINESS_ERROR);
        assertThat(result.getMessage()).isEqualTo("File operation failed: /existing");
        assertThat(StudioRequestContextHolder.getContext()).isNull();
    }

    @Test
    void shouldSanitizeWorkerFileOperationFailureBeforeReturningEnvelope() {
        Fixture fixture = fixture();
        RuntimeDatasourceProbeRequest request = storedRequest();
        request.setFileOperation("CREATE_DIRECTORY");
        request.setOperationPath("/denied");
        DataSourceDefinition canonical = datasource(301L, "tenant-a", 10L);
        when(fixture.dataSourceService.getInternal(301L)).thenReturn(canonical);
        when(fixture.bindingMapper.selectCount(any())).thenReturn(1L);
        doThrow(new IllegalStateException(
                "File operation failed: Permission denied Authorization=Bearer secret-token\r\n"
                        + "\tat example.Plugin.mkdir(Plugin.java:42)"))
                .when(fixture.executor).operate(canonical, "CREATE_DIRECTORY",
                        "/denied", null, null);

        Result<Void> result = fixture.controller.fileOperation(
                "internal-secret", "operation-safe", request);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(StudioErrorCode.BUSINESS_ERROR);
        assertThat(result.getMessage())
                .isEqualTo("File operation failed: Permission denied Authorization=******")
                .doesNotContain("secret-token", "Plugin.java", "\r", "\n");
        assertThat(StudioRequestContextHolder.getContext()).isNull();
    }

    @Test
    void shouldStreamUploadThroughCanonicalDatasource() throws Exception {
        Fixture fixture = fixture();
        RuntimeDatasourceUploadRequest request = uploadRequest();
        DataSourceDefinition canonical = datasource(301L, "tenant-a", 10L);
        when(fixture.dataSourceService.getInternal(301L)).thenReturn(canonical);
        when(fixture.bindingMapper.selectCount(any())).thenReturn(1L);
        when(fixture.executor.upload(org.mockito.ArgumentMatchers.eq(canonical),
                org.mockito.ArgumentMatchers.eq("/upload.txt"),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.any())).thenReturn(5L);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setContent("hello".getBytes(StandardCharsets.UTF_8));

        var response = fixture.controller.fileUpload("internal-secret",
                encode(request), servletRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getData().getBytes()).isEqualTo(5L);
        assertThat(StudioRequestContextHolder.getContext()).isNull();
    }

    @Test
    void shouldReturnConflictEnvelopeForExistingUploadTarget() throws Exception {
        Fixture fixture = fixture();
        RuntimeDatasourceUploadRequest request = uploadRequest();
        DataSourceDefinition canonical = datasource(301L, "tenant-a", 10L);
        when(fixture.dataSourceService.getInternal(301L)).thenReturn(canonical);
        when(fixture.bindingMapper.selectCount(any())).thenReturn(1L);
        doThrow(new IllegalStateException("File upload failed: /upload.txt",
                new FileAlreadyExistsException("/upload.txt")))
                .when(fixture.executor).upload(org.mockito.ArgumentMatchers.eq(canonical),
                        org.mockito.ArgumentMatchers.eq("/upload.txt"),
                        org.mockito.ArgumentMatchers.eq(false),
                        org.mockito.ArgumentMatchers.eq(5L),
                        org.mockito.ArgumentMatchers.any());
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setContent("hello".getBytes(StandardCharsets.UTF_8));

        var response = fixture.controller.fileUpload("internal-secret",
                encode(request), servletRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getCode()).isEqualTo(StudioErrorCode.CONFLICT);
    }

    @Test
    void shouldSanitizeWorkerUploadFailureBeforeReturningEnvelope() throws Exception {
        Fixture fixture = fixture();
        RuntimeDatasourceUploadRequest request = uploadRequest();
        DataSourceDefinition canonical = datasource(301L, "tenant-a", 10L);
        when(fixture.dataSourceService.getInternal(301L)).thenReturn(canonical);
        when(fixture.bindingMapper.selectCount(any())).thenReturn(1L);
        doThrow(new IllegalStateException(
                "File upload failed: Permission denied token=secret-token\n"
                        + "\tat example.Plugin.put(Plugin.java:17)"))
                .when(fixture.executor).upload(org.mockito.ArgumentMatchers.eq(canonical),
                        org.mockito.ArgumentMatchers.eq("/upload.txt"),
                        org.mockito.ArgumentMatchers.eq(false),
                        org.mockito.ArgumentMatchers.eq(5L),
                        org.mockito.ArgumentMatchers.any());
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setContent("hello".getBytes(StandardCharsets.UTF_8));

        var response = fixture.controller.fileUpload(
                "internal-secret", "operation-safe", encode(request), servletRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getCode()).isEqualTo(StudioErrorCode.BUSINESS_ERROR);
        assertThat(response.getBody().getMessage())
                .isEqualTo("File upload failed: Permission denied token=******")
                .doesNotContain("secret-token", "Plugin.java", "\r", "\n");
    }

    private Fixture fixture() {
        RuntimeDatasourceProbeExecutor executor = mock(RuntimeDatasourceProbeExecutor.class);
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        WorkerAuthorizationService authorizationService = mock(WorkerAuthorizationService.class);
        DatasourceClusterBindingMapper bindingMapper = mock(DatasourceClusterBindingMapper.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setInternalApiToken("internal-secret");
        properties.setRuntimeClusterCode("C50");
        RuntimeClusterEntity cluster = new RuntimeClusterEntity();
        cluster.setId(50L);
        cluster.setTenantId("tenant-a");
        cluster.setCode("C50");
        cluster.setEnabled(1);
        when(clusterMapper.selectById(50L)).thenReturn(cluster);
        when(authorizationService.isRuntimeClusterAuthorizedForProject("tenant-a", 20L, 50L))
                .thenReturn(true);
        InternalDatasourceProbeController controller = new InternalDatasourceProbeController(
                executor, properties, clusterMapper, authorizationService, bindingMapper,
                dataSourceService, new ObjectMapper());
        controller.setUnstructuredFileExecutor(new UnstructuredFileExecutor(executor));
        return new Fixture(controller, executor, bindingMapper, dataSourceService);
    }

    private RuntimeDatasourceProbeRequest storedRequest() {
        RuntimeDatasourceProbeRequest request = baseRequest(RuntimeDatasourceProbeMode.STORED);
        request.setDatasource(datasource(301L, "tenant-a", 10L));
        return request;
    }

    private RuntimeDatasourceUploadRequest uploadRequest() {
        RuntimeDatasourceUploadRequest request = new RuntimeDatasourceUploadRequest();
        request.setTargetClusterId(50L);
        request.setTargetClusterCode("C50");
        request.setTenantId("tenant-a");
        request.setProjectId(20L);
        request.setUserId(99L);
        request.setUsername("admin");
        request.setMode(RuntimeDatasourceProbeMode.STORED);
        request.setDatasource(datasource(301L, "tenant-a", 10L));
        request.setTargetPath("/upload.txt");
        request.setContentLength(5L);
        request.setOverwrite(false);
        return request;
    }

    private String encode(RuntimeDatasourceUploadRequest request) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                new ObjectMapper().writeValueAsBytes(request));
    }

    private RuntimeDatasourceProbeRequest baseRequest(RuntimeDatasourceProbeMode mode) {
        RuntimeDatasourceProbeRequest request = new RuntimeDatasourceProbeRequest();
        request.setTargetClusterId(50L);
        request.setTargetClusterCode("C50");
        request.setTenantId("tenant-a");
        request.setProjectId(20L);
        request.setUserId(99L);
        request.setUsername("admin");
        request.setMode(mode);
        return request;
    }

    private DataSourceDefinition datasource(Long id, String tenantId, Long projectId) {
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(id);
        datasource.setTenantId(tenantId);
        datasource.setProjectId(projectId);
        datasource.setName("orders");
        datasource.setTypeCode("MYSQL");
        return datasource;
    }

    private static final class Fixture {
        private final InternalDatasourceProbeController controller;
        private final RuntimeDatasourceProbeExecutor executor;
        private final DatasourceClusterBindingMapper bindingMapper;
        private final DataSourceService dataSourceService;

        private Fixture(InternalDatasourceProbeController controller,
                        RuntimeDatasourceProbeExecutor executor,
                        DatasourceClusterBindingMapper bindingMapper,
                        DataSourceService dataSourceService) {
            this.controller = controller;
            this.executor = executor;
            this.bindingMapper = bindingMapper;
            this.dataSourceService = dataSourceService;
        }
    }
}
