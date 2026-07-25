package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeClusterSelectionServiceTest {

    @Test
    void shouldRequireClusterForNewResource() {
        RuntimeClusterService runtimeClusterService = mock(RuntimeClusterService.class);
        DatasourceClusterBindingService bindingService = mock(DatasourceClusterBindingService.class);
        RuntimeClusterSelectionService service = new RuntimeClusterSelectionService(runtimeClusterService, bindingService);

        assertThrows(com.jdragon.studio.commons.exception.StudioException.class,
                () -> service.resolveForResourceSave(100L, null, null, false));
    }

    @Test
    void shouldRejectExistingUnassignedResourceUntilItIsMigrated() {
        RuntimeClusterService runtimeClusterService = mock(RuntimeClusterService.class);
        DatasourceClusterBindingService bindingService = mock(DatasourceClusterBindingService.class);
        RuntimeClusterSelectionService service = new RuntimeClusterSelectionService(runtimeClusterService, bindingService);

        assertThrows(com.jdragon.studio.commons.exception.StudioException.class,
                () -> service.resolveForResourceSave(100L, null, null, true));
    }

    @Test
    void shouldRejectClearingExistingPlacement() {
        RuntimeClusterService runtimeClusterService = mock(RuntimeClusterService.class);
        DatasourceClusterBindingService bindingService = mock(DatasourceClusterBindingService.class);
        RuntimeClusterSelectionService service = new RuntimeClusterSelectionService(runtimeClusterService, bindingService);

        assertThrows(com.jdragon.studio.commons.exception.StudioException.class,
                () -> service.resolveForResourceSave(100L, null, 46L, true));
    }

    @Test
    void shouldValidateExplicitDatasourceBindings() {
        RuntimeClusterService runtimeClusterService = mock(RuntimeClusterService.class);
        DatasourceClusterBindingService bindingService = mock(DatasourceClusterBindingService.class);
        RuntimeClusterEntity cluster = new RuntimeClusterEntity();
        cluster.setId(46L);
        when(runtimeClusterService.requireAuthorized(100L, 46L)).thenReturn(cluster);
        RuntimeClusterSelectionService service = new RuntimeClusterSelectionService(runtimeClusterService, bindingService);

        Long selected = service.validateDatasourceSelection(100L, 46L, List.of(11L, 12L, 11L));

        assertEquals(46L, selected);
        verify(bindingService).assertDatasourceApplicable(11L, 46L);
        verify(bindingService).assertDatasourceApplicable(12L, 46L);
    }

    @Test
    void shouldRejectMissingClusterForExplicitRuntimeOperation() {
        RuntimeClusterService runtimeClusterService = mock(RuntimeClusterService.class);
        DatasourceClusterBindingService bindingService = mock(DatasourceClusterBindingService.class);
        RuntimeClusterSelectionService service = new RuntimeClusterSelectionService(runtimeClusterService, bindingService);

        assertThrows(com.jdragon.studio.commons.exception.StudioException.class,
                () -> service.assertExplicitSelection(null));
        assertThrows(com.jdragon.studio.commons.exception.StudioException.class,
                () -> service.validateExplicitDatasourceSelection(100L, null, List.of(11L)));
    }
}
