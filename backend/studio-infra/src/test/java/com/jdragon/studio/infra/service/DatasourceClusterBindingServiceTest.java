package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.entity.DatasourceClusterBindingEntity;
import com.jdragon.studio.infra.mapper.DatasourceClusterBindingMapper;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasourceClusterBindingServiceTest {

    @Test
    void shouldKeepAllDatasourceOptionsWhenRuntimeClusterIsOmitted() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        DatasourceClusterBindingMapper bindingMapper = mock(DatasourceClusterBindingMapper.class);
        RuntimeClusterService clusterService = mock(RuntimeClusterService.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(clusterMapper.selectCount(any())).thenReturn(0L);

        DatasourceClusterBindingService service = new DatasourceClusterBindingService(bindingMapper, clusterMapper, clusterService, security);

        Set<Long> result = service.filterApplicableDatasourceIds(100L, null, List.of(7L, 8L, 7L));

        assertEquals(new LinkedHashSet<Long>(List.of(7L, 8L)), result);
        verify(clusterService, never()).requireAuthorized(any(), any());
        verify(bindingMapper, never()).selectList(any());
    }

    @Test
    void shouldFilterBoundDatasourcesAndRequireClusterAuthorization() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        DatasourceClusterBindingMapper bindingMapper = mock(DatasourceClusterBindingMapper.class);
        RuntimeClusterService clusterService = mock(RuntimeClusterService.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(clusterMapper.selectCount(any())).thenReturn(2L);
        DatasourceClusterBindingEntity first = binding(7L, 10L);
        when(bindingMapper.selectList(any())).thenReturn(List.of(first));
        DatasourceClusterBindingService service = new DatasourceClusterBindingService(bindingMapper, clusterMapper, clusterService, security);

        Set<Long> result = service.filterApplicableDatasourceIds(100L, 10L, List.of(7L, 8L, 9L));

        assertEquals(new LinkedHashSet<Long>(List.of(7L)), result);
        verify(clusterService).requireAuthorized(100L, 10L);
    }

    @Test
    void shouldRequireAtLeastOneApplicableClusterAfterRegistryIsConfigured() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(clusterMapper.selectCount(any())).thenReturn(1L);
        DatasourceClusterBindingService service = new DatasourceClusterBindingService(mock(DatasourceClusterBindingMapper.class),
                clusterMapper, mock(RuntimeClusterService.class), security);
        assertThrows(StudioException.class, () -> service.normalizeForSave(100L, List.of()));
    }

    @Test
    void shouldRequireApplicableClusterForNewDatasource() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(clusterMapper.selectCount(any())).thenReturn(1L);
        DatasourceClusterBindingService service = new DatasourceClusterBindingService(
                mock(DatasourceClusterBindingMapper.class), clusterMapper,
                mock(RuntimeClusterService.class), security);

        assertThrows(StudioException.class, () -> service.normalizeForSave(100L, null, List.of()));
    }

    @Test
    void shouldRejectExistingUnboundDatasourceUntilItIsMigrated() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        DatasourceClusterBindingMapper bindingMapper = mock(DatasourceClusterBindingMapper.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(clusterMapper.selectCount(any())).thenReturn(1L);
        when(bindingMapper.selectList(any())).thenReturn(List.of());
        DatasourceClusterBindingService service = new DatasourceClusterBindingService(
                bindingMapper, clusterMapper, mock(RuntimeClusterService.class), security);

        assertThrows(StudioException.class, () -> service.normalizeForSave(100L, 7L, List.of()));
    }

    @Test
    void shouldRejectClearingExistingDatasourceBindings() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        DatasourceClusterBindingMapper bindingMapper = mock(DatasourceClusterBindingMapper.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(clusterMapper.selectCount(any())).thenReturn(1L);
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding(7L, 10L)));
        DatasourceClusterBindingService service = new DatasourceClusterBindingService(
                bindingMapper, clusterMapper, mock(RuntimeClusterService.class), security);

        assertThrows(StudioException.class, () -> service.normalizeForSave(100L, 7L, List.of()));
    }

    @Test
    void shouldRejectSaveBeforeAnyRuntimeClusterIsConfigured() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(clusterMapper.selectCount(any())).thenReturn(0L);
        DatasourceClusterBindingService service = new DatasourceClusterBindingService(
                mock(DatasourceClusterBindingMapper.class), clusterMapper,
                mock(RuntimeClusterService.class), security);
        assertThrows(StudioException.class, () -> service.normalizeForSave(100L, List.of(10L)));
    }

    private DatasourceClusterBindingEntity binding(Long datasourceId, Long runtimeClusterId) {
        DatasourceClusterBindingEntity entity = new DatasourceClusterBindingEntity();
        entity.setDatasourceId(datasourceId);
        entity.setRuntimeClusterId(runtimeClusterId);
        entity.setEnabled(1);
        return entity;
    }
}
