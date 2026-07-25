package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.entity.DatasourceEntity;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataSourceSharedRuntimeClusterGuardTest {

    @Test
    void shouldUseReceivingProjectAuthorizationAndSourceDatasourceBinding() {
        Fixture fixture = fixture();

        ReflectionTestUtils.invokeMethod(fixture.service, "assertApplicableToRuntimeCluster",
                sourceDatasource(), 50L);

        verify(fixture.bindingService).filterApplicableDatasourceIds(
                200L, 50L, Collections.singletonList(11L));
        verify(fixture.bindingService).assertDatasourceApplicable(11L, 50L);
    }

    @Test
    void shouldRejectSharedDatasourceWhenReceivingProjectIsNotAuthorized() {
        Fixture fixture = fixture();
        when(fixture.bindingService.filterApplicableDatasourceIds(
                200L, 50L, Collections.singletonList(11L)))
                .thenThrow(new StudioException(StudioErrorCode.FORBIDDEN,
                        "Runtime cluster is not authorized for this project"));

        assertThrows(StudioException.class, () -> ReflectionTestUtils.invokeMethod(
                fixture.service, "assertApplicableToRuntimeCluster", sourceDatasource(), 50L));

        verify(fixture.bindingService, never()).assertDatasourceApplicable(any(), any());
    }

    @Test
    void shouldRejectSharedDatasourceWhenSourceBindingDoesNotIncludeCluster() {
        Fixture fixture = fixture();
        when(fixture.bindingService.filterApplicableDatasourceIds(
                200L, 50L, Collections.singletonList(11L)))
                .thenReturn(Collections.singleton(11L));
        doThrow(new StudioException(StudioErrorCode.BUSINESS_ERROR,
                "Datasource is not applicable to the selected runtime cluster"))
                .when(fixture.bindingService).assertDatasourceApplicable(11L, 50L);

        assertThrows(StudioException.class, () -> ReflectionTestUtils.invokeMethod(
                fixture.service, "assertApplicableToRuntimeCluster", sourceDatasource(), 50L));
    }

    private Fixture fixture() {
        ProjectResourceAccessService projectResourceAccessService = mock(ProjectResourceAccessService.class);
        when(projectResourceAccessService.requireCurrentProjectId()).thenReturn(200L);
        DatasourceClusterBindingService bindingService = mock(DatasourceClusterBindingService.class);
        when(bindingService.filterApplicableDatasourceIds(eq(200L), eq(50L), any()))
                .thenReturn(Collections.singleton(11L));
        DataSourceService service = new DataSourceService(
                mock(DatasourceMapper.class),
                mock(DataModelMapper.class),
                mock(EncryptionService.class),
                mock(MetadataSchemaService.class),
                mock(DataModelIndexRebuildQueueService.class),
                mock(BusinessMetaModelMetadataService.class),
                mock(StudioSecurityService.class),
                projectResourceAccessService,
                mock(DatasourceTypeCapabilityService.class),
                mock(DatasourceConnectionFingerprintService.class),
                mock(DatasourceConnectionHealthService.class));
        service.setDatasourceClusterBindingService(bindingService);
        return new Fixture(service, bindingService);
    }

    private DatasourceEntity sourceDatasource() {
        DatasourceEntity entity = new DatasourceEntity();
        entity.setId(11L);
        entity.setTenantId("tenant-a");
        entity.setProjectId(100L);
        return entity;
    }

    private static final class Fixture {
        private final DataSourceService service;
        private final DatasourceClusterBindingService bindingService;

        private Fixture(DataSourceService service, DatasourceClusterBindingService bindingService) {
            this.service = service;
            this.bindingService = bindingService;
        }
    }
}
