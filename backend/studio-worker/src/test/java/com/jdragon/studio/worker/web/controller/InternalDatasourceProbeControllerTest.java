package com.jdragon.studio.worker.web.controller;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.enums.RuntimeDatasourceProbeMode;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.dto.ConnectionTestResult;
import com.jdragon.studio.dto.model.request.RuntimeDatasourceProbeRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.mapper.DatasourceClusterBindingMapper;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.security.StudioRequestContext;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.RuntimeDatasourceProbeExecutor;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalDatasourceProbeControllerTest {

    @AfterEach
    void clearContext() {
        StudioRequestContextHolder.clear();
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
                executor, properties, clusterMapper, authorizationService, bindingMapper, dataSourceService);
        return new Fixture(controller, executor, bindingMapper, dataSourceService);
    }

    private RuntimeDatasourceProbeRequest storedRequest() {
        RuntimeDatasourceProbeRequest request = baseRequest(RuntimeDatasourceProbeMode.STORED);
        request.setDatasource(datasource(301L, "tenant-a", 10L));
        return request;
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
